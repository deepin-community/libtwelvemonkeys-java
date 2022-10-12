/*
 * Copyright (c) 2017, Brooss, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 *   this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.imageio.plugins.webp.vp8;

import javax.imageio.IIOException;
import javax.imageio.ImageReadParam;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.twelvemonkeys.imageio.color.YCbCrConverter.convertYCbCr2RGB;

public final class VP8Frame {
    private static final int BLOCK_TYPES = 4;
    private static final int COEF_BANDS = 8;
    private static final int MAX_ENTROPY_TOKENS = 12;
    private static final int MAX_MODE_LF_DELTAS = 4;
    private static final int MAX_REF_LF_DELTAS = 4;
    private static final int PREV_COEF_CONTEXTS = 3;

    private IIOReadProgressListener listener = null;

    //    private int bufferCount;
//    private int buffersToCreate = 1;
    private final int[][][][] coefProbs;
    private int filterLevel;

    private final ImageInputStream frame;
    private final boolean debug;
    private int frameType;
    private int height;

    private int macroBlockCols;
    private int macroBlockNoCoeffSkip;
    private int macroBlockRows;

    private MacroBlock[][] macroBlocks;
    private int macroBlockSegementAbsoluteDelta;
    private int[] macroBlockSegmentTreeProbs;
    private final int[] modeLoopFilterDeltas = new int[MAX_MODE_LF_DELTAS];
    private int modeRefLoopFilterDeltaEnabled;
    private int modeRefLoopFilterDeltaUpdate;
    private int multiTokenPartition = 0;

    private long offset;
    private final int[] refLoopFilterDeltas = new int[MAX_REF_LF_DELTAS];
    private int refreshEntropyProbs;
    private int refreshLastFrame;
    private int segmentationIsEnabled;
    private SegmentQuants segmentQuants;
    private int sharpnessLevel;
    private boolean simpleFilter;
    private BoolDecoder tokenBoolDecoder;
    private final List<BoolDecoder> tokenBoolDecoders;
    private int updateMacroBlockSegmentationMap;
    private int updateMacroBlockSegmentatonData;
    private int width;

    public VP8Frame(final ImageInputStream stream, boolean debug) throws IOException {
        this.frame = stream;
        this.debug = debug;

        offset = frame.getStreamPosition();
        coefProbs = Globals.getDefaultCoefProbs();
        tokenBoolDecoders = new ArrayList<>();
    }

    public void setProgressListener(IIOReadProgressListener listener) {
        this.listener = listener;
    }

    private void createMacroBlocks() {
        macroBlocks = new MacroBlock[macroBlockRows + 2][macroBlockCols + 2];
        for (int y = 0; y < macroBlockRows + 2; y++) {
            for (int x = 0; x < macroBlockCols + 2; x++) {
                macroBlocks[y][x] = new MacroBlock(x, y, debug);
            }
        }
    }

    public boolean decode(final WritableRaster raster, final ImageReadParam param) throws IOException {
        segmentQuants = new SegmentQuants();

        int c = frame.readUnsignedByte();
        frameType = getBitAsInt(c, 0);
//        logger.log("Frame type: " + frameType);

        if (frameType != 0) {
            return false;
        }

        int versionNumber = getBitAsInt(c, 1) << 1;
        versionNumber += getBitAsInt(c, 2) << 1;
        versionNumber += getBitAsInt(c, 3);

//        logger.log("Version Number: " + versionNumber);
//        logger.log("show_frame: " + getBit(c, 4));

        int firstPartitionLengthInBytes;
        firstPartitionLengthInBytes = getBitAsInt(c, 5)/*<< 0*/;
        firstPartitionLengthInBytes += getBitAsInt(c, 6) << 1;
        firstPartitionLengthInBytes += getBitAsInt(c, 7) << 2;

        c = frame.readUnsignedByte();
        firstPartitionLengthInBytes += c << 3;

        c = frame.readUnsignedByte();
        firstPartitionLengthInBytes += c << 11;
//        logger.log("first_partition_length_in_bytes: "+ firstPartitionLengthInBytes);

        c = frame.readUnsignedByte();
//        logger.log("StartCode: " + c);

        c = frame.readUnsignedByte();
//        logger.log(" " + c);

        c = frame.readUnsignedByte();
//        logger.log(" " + c);

        c = frame.readUnsignedByte();
        int hBytes = c;
        c = frame.readUnsignedByte();
        hBytes += c << 8;
        width = (hBytes & 0x3fff);
//        logger.log("width: " + width);
//        logger.log("hScale: " + (hBytes >> 14));

        c = frame.readUnsignedByte();
        int vBytes = c;
        c = frame.readUnsignedByte();
        vBytes += c << 8;
        height = (vBytes & 0x3fff);
//        logger.log("height: " + height);
//        logger.log("vScale: " + (vBytes >> 14));
        int tWidth = width;
        int tHeight = height;
        if ((tWidth & 0xf) != 0) {
            tWidth += 16 - (tWidth & 0xf);
        }

        if ((tHeight & 0xf) != 0) {
            tHeight += 16 - (tHeight & 0xf);
        }
        macroBlockRows = tHeight >> 4;
        macroBlockCols = tWidth >> 4;
//        logger.log("macroBlockCols: " + macroBlockCols);
//        logger.log("macroBlockRows: " + macroBlockRows);

        createMacroBlocks();

        offset = frame.getStreamPosition();

        BoolDecoder bc = new BoolDecoder(frame, offset);

        if (frameType == 0) {
            int clr_type = bc.readBit();
//            logger.log("clr_type: " + clr_type);
//            logger.log("" + bc);

            int clamp_type = bc.readBit();
//            logger.log("clamp_type: " + clamp_type);

        }
        segmentationIsEnabled = bc.readBit();
//        logger.log("segmentation_enabled: " + segmentationIsEnabled);

        if (segmentationIsEnabled > 0) {
            // TODO: The original code logged a TODO warning here, but what is left to do?
            updateMacroBlockSegmentationMap = bc.readBit();
            updateMacroBlockSegmentatonData = bc.readBit();
//            logger.log("update_mb_segmentaton_map: " + updateMacroBlockSegmentationMap);
//            logger.log("update_mb_segmentaton_data: " + updateMacroBlockSegmentatonData);

            if (updateMacroBlockSegmentatonData > 0) {
                macroBlockSegementAbsoluteDelta = bc.readBit();
                /* For each segmentation feature (Quant and loop filter level) */
                for (int i = 0; i < Globals.MAX_MB_SEGMENTS; i++) {
                    int value = 0;
                    if (bc.readBit() > 0) {
                        value = bc.readLiteral(Globals.vp8MacroBlockFeatureDataBits[0]);
                        if (bc.readBit() > 0) {
                            value = -value;
                        }
                    }
                    this.segmentQuants.getSegQuants()[i].setQindex(value);
                }
                for (int i = 0; i < Globals.MAX_MB_SEGMENTS; i++) {
                    int value = 0;
                    if (bc.readBit() > 0) {
                        value = bc.readLiteral(Globals.vp8MacroBlockFeatureDataBits[1]);
                        if (bc.readBit() > 0) {
                            value = -value;
                        }
                    }
                    this.segmentQuants.getSegQuants()[i].setFilterStrength(value);
                }

                if (updateMacroBlockSegmentationMap > 0) {
                    macroBlockSegmentTreeProbs = new int[Globals.MB_FEATURE_TREE_PROBS];
                    for (int i = 0; i < Globals.MB_FEATURE_TREE_PROBS; i++) {
                        int value = bc.readBit() > 0 ? bc.readLiteral(8) : 255;
                        macroBlockSegmentTreeProbs[i] = value;
                    }
                }
            }
        }

        simpleFilter = bc.readBit() != 0;
//        logger.log("simpleFilter: " + simpleFilter);
        filterLevel = bc.readLiteral(6);

//        logger.log("filter_level: " + filterLevel);
        sharpnessLevel = bc.readLiteral(3);
//        logger.log("sharpness_level: " + sharpnessLevel);
        modeRefLoopFilterDeltaEnabled = bc.readBit();
//        logger.log("mode_ref_lf_delta_enabled: " + modeRefLoopFilterDeltaEnabled);

        if (modeRefLoopFilterDeltaEnabled > 0) {
            // Do the deltas need to be updated
            modeRefLoopFilterDeltaUpdate = bc.readBit();
//            logger.log("mode_ref_lf_delta_update: " + modeRefLoopFilterDeltaUpdate);
            if (modeRefLoopFilterDeltaUpdate > 0) {
                for (int i = 0; i < MAX_REF_LF_DELTAS; i++) {
                    if (bc.readBit() > 0) {
                        refLoopFilterDeltas[i] = bc.readLiteral(6);
                        if (bc.readBit() > 0) { // Apply sign
                            refLoopFilterDeltas[i] = refLoopFilterDeltas[i] * -1;
                        }
//                        logger.log("ref_lf_deltas[i]: " + refLoopFilterDeltas[i]);
                    }
                }
                for (int i = 0; i < MAX_MODE_LF_DELTAS; i++) {
                    if (bc.readBit() > 0) {
                        modeLoopFilterDeltas[i] = bc.readLiteral(6);
                        if (bc.readBit() > 0) { // Apply sign
                            modeLoopFilterDeltas[i] = modeLoopFilterDeltas[i] * -1;
                        }
//                        logger.log("mode_lf_deltas[i]: " + modeLoopFilterDeltas[i]);
                    }
                }
            }
        }

        setupTokenDecoder(bc, firstPartitionLengthInBytes, offset);
        bc.seek();

        segmentQuants.parse(bc, segmentationIsEnabled == 1, macroBlockSegementAbsoluteDelta == 1);

        // Determine if the golden frame or ARF buffer should be updated and how.
        // For all non key frames the GF and ARF refresh flags and sign bias
        // flags must be set explicitly.
        if (frameType != 0) {
            throw new IllegalArgumentException("Bad input: Not an Intra frame");
        }

        refreshEntropyProbs = bc.readBit();
//        logger.log("refresh_entropy_probs: " + refreshEntropyProbs);

        if (refreshEntropyProbs > 0) {
            // TODO? Original code has nothing here...
        }

        refreshLastFrame = 0;
        if (frameType == 0) {
            refreshLastFrame = 1;
        }
        else {
            refreshLastFrame = bc.readBit();
        }

//        logger.log("refresh_last_frame: " + refreshLastFrame);

        for (int i = 0; i < BLOCK_TYPES; i++) {
            for (int j = 0; j < COEF_BANDS; j++) {
                for (int k = 0; k < PREV_COEF_CONTEXTS; k++) {
                    for (int l = 0; l < MAX_ENTROPY_TOKENS - 1; l++) {
                        if (bc.readBool(Globals.vp8CoefUpdateProbs[i][j][k][l]) > 0) {
                            int newp = bc.readLiteral(8);
                            this.coefProbs[i][j][k][l] = newp;
                        }
                    }
                }
            }
        }

        // Read the mb_no_coeff_skip flag
        macroBlockNoCoeffSkip = bc.readBit();
//        logger.log("mb_no_coeff_skip: " + macroBlockNoCoeffSkip);

        if (frameType == 0) {
            readModes(bc);
        }
        else {
            throw new IIOException("Bad input: Not an Intra frame");
        }

        int ibc = 0;
        int parts = 1 << multiTokenPartition;

        Rectangle region = param != null && param.getSourceRegion() != null ? param.getSourceRegion() : raster.getBounds();
        int sourceXSubsampling = param != null ? param.getSourceXSubsampling() : 1;
        int sourceYSubsampling = param != null ? param.getSourceYSubsampling() : 1;

        for (int row = 0; row < macroBlockRows; row++) {
            if (parts > 1) {
                tokenBoolDecoder = tokenBoolDecoders.get(ibc);
                tokenBoolDecoder.seek();

                ibc++;
                if (ibc == parts) {
                    ibc = 0;
                }
            }

            decodeMacroBlockRow(row, raster, region, sourceXSubsampling, sourceYSubsampling);

            fireProgressUpdate(row);
        }

        return true;
    }

    private void decodeMacroBlockRow(final int mbRow, final WritableRaster raster, final Rectangle region,
                                     final int xSubsampling, final int ySubsampling) throws IOException {
        final boolean filter = filterLevel != 0;

        MacroBlock left = null;
        MacroBlock[] prevRow = macroBlocks[mbRow];
        MacroBlock[] currRow = macroBlocks[mbRow + 1];

        for (int mbCol = 0; mbCol < macroBlockCols; mbCol++) {
            MacroBlock mb = currRow[mbCol + 1];

            mb.decodeMacroBlock(this);
            mb.dequantMacroBlock(this);

            if (filter) {
                MacroBlock top = mbRow > 0 ? prevRow[mbCol + 1] : null;
                LoopFilter.loopFilterBlock(mb, left, top, frameType, simpleFilter, sharpnessLevel);
            }

            copyBlock(mb, raster, region, xSubsampling, ySubsampling);

            left = mb;
        }
    }

    private void fireProgressUpdate(int mbRow) {
        if (listener != null) {
            float percentageDone = (100.0f * ((float) (mbRow + 1) / (float) getMacroBlockRows()));
            listener.imageProgress(null, percentageDone);
        }
    }

    public SubBlock getAboveRightSubBlock(SubBlock sb, SubBlock.Plane plane) {
        // this might break at right edge
        SubBlock r;
        MacroBlock mb = sb.getMacroBlock();
        int x = mb.getSubblockX(sb);
        int y = mb.getSubblockY(sb);

        if (plane == SubBlock.Plane.Y1) {
            // top row
            if (y == 0 && x < 3) {
                MacroBlock mb2 = this.getMacroBlock(mb.getX(), mb.getY() - 1);
                r = mb2.getSubBlock(plane, x + 1, 3);
                return r;
            }
            //top right
            else if (y == 0 && x == 3) {
                MacroBlock mb2 = this.getMacroBlock(mb.getX() + 1, mb.getY() - 1);
                r = mb2.getSubBlock(plane, 0, 3);

                if (mb2.getX() == this.getMacroBlockCols()) {

                    int[][] dest = new int[4][4];
                    for (int b = 0; b < 4; b++) {
                        for (int a = 0; a < 4; a++) {
                            if (mb2.getY() < 0) {
                                dest[a][b] = 127;
                            }
                            else {
                                dest[a][b] = this.getMacroBlock(mb.getX(), mb.getY() - 1).getSubBlock(SubBlock.Plane.Y1, 3, 3).getDest()[3][3];
                            }
                        }
                    }
                    r = new SubBlock(mb2, null, null, SubBlock.Plane.Y1);
                    r.setDest(dest);
                }

                return r;
            }
            //not right edge or top row
            else if (y > 0 && x < 3) {
                r = mb.getSubBlock(plane, x + 1, y - 1);
                return r;
            }
            //else use top right
            else {
                SubBlock sb2 = mb.getSubBlock(sb.getPlane(), 3, 0);
                return this.getAboveRightSubBlock(sb2, plane);
            }
        }
        else {
            // TODO
            throw new IllegalArgumentException("bad input: getAboveRightSubBlock()");
        }
    }

    public SubBlock getAboveSubBlock(SubBlock sb, SubBlock.Plane plane) {
        SubBlock above = sb.getAbove();

        if (above == null) {
            MacroBlock mb = sb.getMacroBlock();
            int x = mb.getSubblockX(sb);

            MacroBlock mb2 = getMacroBlock(mb.getX(), mb.getY() - 1);
            //TODO: SPLIT
            while (plane == SubBlock.Plane.Y2 && mb2.getYMode() == Globals.B_PRED) {
                mb2 = getMacroBlock(mb2.getX(), mb2.getY() - 1);
            }

            above = mb2.getBottomSubBlock(x, sb.getPlane());
        }

        return above;
    }

//     private boolean getBit(int data, int bit) {
//         int r = data & (1 << bit);
//         return r != 0;
//     }

    private int getBitAsInt(int data, int bit) {
        int r = data & (1 << bit);
        if (r != 0) {
            return 1;
        }
        return 0;
    }

    int[][][][] getCoefProbs() {
        return coefProbs;
    }

    public BufferedImage getDebugImageDiff() {

        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int yy, u, v;
                yy = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.Y1, (x % 16) / 4, (y % 16) / 4).getDiff()[x % 4][y % 4];
                u = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.U, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDiff()[(x / 2) % 4][(y / 2) % 4];
                v = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.V, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDiff()[(x / 2) % 4][(y / 2) % 4];
                c[0] = (int) (1.164 * (yy - 16) + 1.596 * (v - 128));
                c[1] = (int) (1.164 * (yy - 16) - 0.813 * (v - 128) - 0.391 * (u - 128));
                c[2] = (int) (1.164 * (yy - 16) + 2.018 * (u - 128));

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImagePredict() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int yy, u, v;
                yy = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.Y1, (x % 16) / 4, (y % 16) / 4).getPredict()[x % 4][y % 4];
                u = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.U, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getPredict()[(x / 2) % 4][(y / 2) % 4];
                v = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.V, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getPredict()[(x / 2) % 4][(y / 2) % 4];
                c[0] = (int) (1.164 * (yy - 16) + 1.596 * (v - 128));
                c[1] = (int) (1.164 * (yy - 16) - 0.813 * (v - 128) - 0.391 * (u - 128));
                c[2] = (int) (1.164 * (yy - 16) + 2.018 * (u - 128));

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageUBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int u;
                u = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.U, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDest()[(x / 2) % 4][(y / 2) % 4];
                c[0] = u;
                c[1] = u;
                c[2] = u;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageUDiffBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int u;
                u = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.U, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDiff()[(x / 2) % 4][(y / 2) % 4];
                c[0] = u;
                c[1] = u;
                c[2] = u;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageUPredBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int u;
                u = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.U, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getPredict()[(x / 2) % 4][(y / 2) % 4];
                c[0] = u;
                c[1] = u;
                c[2] = u;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageVBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int v;
                v = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.V, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDest()[(x / 2) % 4][(y / 2) % 4];
                c[0] = v;
                c[1] = v;
                c[2] = v;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageVDiffBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int v;
                v = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.V, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getDiff()[(x / 2) % 4][(y / 2) % 4];
                c[0] = v;
                c[1] = v;
                c[2] = v;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageVPredBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int v;
                v = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.V, ((x / 2) % 8) / 4, ((y / 2) % 8) / 4).getPredict()[(x / 2) % 4][(y / 2) % 4];
                c[0] = v;
                c[1] = v;
                c[2] = v;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageYBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int yy;
                yy = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.Y1, (x % 16) / 4, (y % 16) / 4).getDest()[x % 4][y % 4];
                c[0] = yy;
                c[1] = yy;
                c[2] = yy;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageYDiffBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int yy;
                yy = 127 + this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.Y1, (x % 16) / 4, (y % 16) / 4).getDiff()[x % 4][y % 4];
                c[0] = yy;
                c[1] = yy;
                c[2] = yy;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public BufferedImage getDebugImageYPredBuffer() {
        BufferedImage bi = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
        WritableRaster imRas = bi.getWritableTile(0, 0);
        for (int x = 0; x < getWidth(); x++) {
            for (int y = 0; y < getHeight(); y++) {
                int[] c = new int[3];
                int yy;
                yy = this.getMacroBlock(x / 16, y / 16).getSubBlock(SubBlock.Plane.Y1, (x % 16) / 4, (y % 16) / 4).getPredict()[x % 4][y % 4];
                c[0] = yy;
                c[1] = yy;
                c[2] = yy;

                for (int z = 0; z < 3; z++) {
                    if (c[z] < 0) {
                        c[z] = 0;
                    }
                    if (c[z] > 255) {
                        c[z] = 255;
                    }
                }
                imRas.setPixel(x, y, c);
            }
        }
//        bufferCount++;
        return bi;
    }

    public int getFrameType() {
        return frameType;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public SubBlock getLeftSubBlock(SubBlock sb, SubBlock.Plane plane) {
        SubBlock r = sb.getLeft();
        if (r == null) {
            MacroBlock mb = sb.getMacroBlock();
            int y = mb.getSubblockY(sb);
            MacroBlock mb2 = getMacroBlock(mb.getX() - 1, mb.getY());
            //TODO: SPLIT

            while (plane == SubBlock.Plane.Y2 && mb2.getYMode() == Globals.B_PRED) {
                mb2 = getMacroBlock(mb2.getX() - 1, mb2.getY());
            }

            r = mb2.getRightSubBlock(y, sb.getPlane());
        }

        return r;
    }

    public MacroBlock getMacroBlock(int mbCol, int mbRow) {
//         return macroBlocks[mbCol + 1][mbRow + 1];
        return macroBlocks[mbRow + 1][mbCol + 1];
    }

    public int getMacroBlockCols() {
        return macroBlockCols;
    }

    public String getMacroBlockDebugString(int mbx, int mby, int sbx, int sby) {
        String r = "";
        if (mbx < this.macroBlockCols && mby < this.getMacroBlockRows()) {
            MacroBlock mb = getMacroBlock(mbx, mby);
            r = r + mb.getDebugString();
            if (sbx < 4 && sby < 4) {
                SubBlock sb = mb.getSubBlock(SubBlock.Plane.Y1, sbx, sby);
                r = r + "\n SubBlock " + sbx + ", " + sby + "\n  " + sb.getDebugString();
                sb = mb.getSubBlock(SubBlock.Plane.Y2, sbx, sby);
                r = r + "\n SubBlock " + sbx + ", " + sby + "\n  " + sb.getDebugString();
                sb = mb.getSubBlock(SubBlock.Plane.U, sbx / 2, sby / 2);
                r = r + "\n SubBlock " + sbx / 2 + ", " + sby / 2 + "\n  " + sb.getDebugString();
                sb = mb.getSubBlock(SubBlock.Plane.V, sbx / 2, sby / 2);
                r = r + "\n SubBlock " + sbx / 2 + ", " + sby / 2 + "\n  " + sb.getDebugString();
            }
        }
        return r;
    }

    public int getMacroBlockRows() {
        return macroBlockRows;
    }

    public int getQIndex() {
        return segmentQuants.getqIndex();
    }

    public SegmentQuants getSegmentQuants() {
        return segmentQuants;
    }

    public int getSharpnessLevel() {
        return sharpnessLevel;
    }

    public BoolDecoder getTokenBoolDecoder() throws IOException {
        tokenBoolDecoder.seek();
        return tokenBoolDecoder;
    }

//     public int[][] getUBuffer() {
//         int[][] r = new int[macroBlockCols * 8][macroBlockRows * 8];
//         for (int y = 0; y < macroBlockRows; y++) {
//             for (int x = 0; x < macroBlockCols; x++) {
//                 MacroBlock mb = macroBlocks[x + 1][y + 1];
//                 for (int b = 0; b < 2; b++) {
//                     for (int a = 0; a < 2; a++) {
//                         SubBlock sb = mb.getUSubBlock(a, b);
//                         for (int d = 0; d < 4; d++) {
//                             for (int c = 0; c < 4; c++) {
//                                 r[(x * 8) + (a * 4) + c][(y * 8) + (b * 4) + d] = sb.getDest()[c][d];
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//         return r;
//     }
//
//     public int[][] getVBuffer() {
//         int[][] r = new int[macroBlockCols * 8][macroBlockRows * 8];
//         for (int y = 0; y < macroBlockRows; y++) {
//             for (int x = 0; x < macroBlockCols; x++) {
//                 MacroBlock mb = macroBlocks[x + 1][y + 1];
//                 for (int b = 0; b < 2; b++) {
//                     for (int a = 0; a < 2; a++) {
//                         SubBlock sb = mb.getVSubBlock(a, b);
//                         for (int d = 0; d < 4; d++) {
//                             for (int c = 0; c < 4; c++) {
//                                 r[(x * 8) + (a * 4) + c][(y * 8) + (b * 4) + d] = sb.getDest()[c][d];
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//         return r;
//     }

//     public int[][] getYBuffer() {
//         int[][] r = new int[macroBlockCols * 16][macroBlockRows * 16];
//         for (int y = 0; y < macroBlockRows; y++) {
//             for (int x = 0; x < macroBlockCols; x++) {
//                 MacroBlock mb = macroBlocks[x + 1][y + 1];
//                 for (int b = 0; b < 4; b++) {
//                     for (int a = 0; a < 4; a++) {
//                         SubBlock sb = mb.getYSubBlock(a, b);
//                         for (int d = 0; d < 4; d++) {
//                             for (int c = 0; c < 4; c++) {
//                                 r[(x * 16) + (a * 4) + c][(y * 16) + (b * 4) + d] = sb.getDest()[c][d];
//                             }
//                         }
//                     }
//                 }
//             }
//         }
//         return r;
//     }

    private void readModes(BoolDecoder bc) throws IOException {
        int mb_row = -1;
        int prob_skip_false = 0;

        if (macroBlockNoCoeffSkip > 0) {
            prob_skip_false = bc.readLiteral(8);
        }

        while (++mb_row < macroBlockRows) {
            int mb_col = -1;
            while (++mb_col < macroBlockCols) {
                //if (this.segmentation_enabled > 0) {
                //	logger.log(Level.SEVERE, "TODO:");
                //	throw new IllegalArgumentException("bad input: segmentation_enabled()");
                //}
                // Read the macroblock coeff skip flag if this feature is in
                // use, else default to 0
                MacroBlock mb = getMacroBlock(mb_col, mb_row);

                if ((segmentationIsEnabled > 0) && (updateMacroBlockSegmentationMap > 0)) {
                    int value = bc.readTree(Globals.macroBlockSegmentTree, this.macroBlockSegmentTreeProbs, 0);
                    mb.setSegmentId(value);
                }

                if (modeRefLoopFilterDeltaEnabled > 0) {
                    int level = filterLevel;
                    level = level + refLoopFilterDeltas[0];
                    level = (level < 0) ? 0 : Math.min(level, 63);
                    mb.setFilterLevel(level);
                }
                else {
                    mb.setFilterLevel(segmentQuants.getSegQuants()[mb.getSegmentId()].getFilterStrength());
                }

                int mb_skip_coeff = macroBlockNoCoeffSkip > 0 ? bc.readBool(prob_skip_false) : 0;

                mb.setSkipCoeff(mb_skip_coeff);

                int y_mode = readYMode(bc);

                mb.setYMode(y_mode);

                if (y_mode == Globals.B_PRED) {
                    for (int i = 0; i < 4; i++) {
                        for (int j = 0; j < 4; j++) {
                            SubBlock sb = mb.getYSubBlock(j, i);
                            SubBlock A = getAboveSubBlock(sb, SubBlock.Plane.Y1);
                            SubBlock L = getLeftSubBlock(sb, SubBlock.Plane.Y1);

                            int mode = readSubBlockMode(bc, A.getMode(), L.getMode());

                            sb.setMode(mode);
                        }
                    }

                    if (modeRefLoopFilterDeltaEnabled > 0) {
                        int level = mb.getFilterLevel();
                        level = level + this.modeLoopFilterDeltas[0];
                        level = (level < 0) ? 0 : Math.min(level, 63);
                        mb.setFilterLevel(level);
                    }
                }
                else {
                    int BMode;

                    switch (y_mode) {
                        case Globals.V_PRED:
                            BMode = Globals.B_VE_PRED;
                            break;
                        case Globals.H_PRED:
                            BMode = Globals.B_HE_PRED;
                            break;
                        case Globals.TM_PRED:
                            BMode = Globals.B_TM_PRED;
                            break;
                        case Globals.DC_PRED:
                        default:
                            BMode = Globals.B_DC_PRED;
                            break;
                    }

                    for (int x = 0; x < 4; x++) {
                        for (int y = 0; y < 4; y++) {
                            SubBlock sb = mb.getYSubBlock(x, y);
                            sb.setMode(BMode);
                        }
                    }
                }
                int mode = readUvMode(bc);
                mb.setUvMode(mode);
            }
        }
    }

    private int readPartitionSize(long l) throws IOException {
        frame.seek(l);
        return frame.readUnsignedByte() + (frame.readUnsignedByte() << 8) + (frame.readUnsignedByte() << 16);
    }

    private int readSubBlockMode(BoolDecoder bc, int A, int L) throws IOException {
        return bc.readTree(Globals.vp8SubBlockModeTree, Globals.vp8KeyFrameSubBlockModeProb[A][L], 0);
    }

    private int readUvMode(BoolDecoder bc) throws IOException {
        return bc.readTree(Globals.vp8UVModeTree, Globals.vp8KeyFrameUVModeProb, 0);
    }

    private int readYMode(BoolDecoder bc) throws IOException {
        return bc.readTree(Globals.vp8KeyFrameYModeTree, Globals.vp8KeyFrameYModeProb, 0);
    }

    private void setupTokenDecoder(BoolDecoder bc, int first_partition_length_in_bytes, long offset) throws IOException {
        long partitionSize;
        long partitionsStart = offset + first_partition_length_in_bytes;
        long partition = partitionsStart;
        multiTokenPartition = bc.readLiteral(2);
        int num_part = 1 << multiTokenPartition;

        if (num_part > 1) {
            partition += 3 * (num_part - 1);
        }
        for (int i = 0; i < num_part; i++) {
            // Calculate the length of this partition. The last partition size is implicit.
            if (i < num_part - 1) {
                partitionSize = readPartitionSize(partitionsStart + (i * 3));
                bc.seek();
            }
            else {
                partitionSize = frame.length() - partition;
            }

            tokenBoolDecoders.add(new BoolDecoder(frame, partition));
            partition += partitionSize;
        }

        tokenBoolDecoder = tokenBoolDecoders.get(0);
    }

    private final byte[] yuv = new byte[3];
    private final byte[] rgb = new byte[4]; // Allow decoding into RGBA, leaving the alpha out.

    private void copyBlock(final MacroBlock macroBlock, final WritableRaster byteRGBRaster,
                           final Rectangle region, final int xSubsampling, final int ySubsampling) {
        // We might be copying into a smaller raster
        int yStart = macroBlock.getY() * 16 - region.y;
        int yEnd = Math.min(16, byteRGBRaster.getHeight() * ySubsampling - yStart);
        int xStart = macroBlock.getX() * 16 - region.x;
        int xEnd = Math.min(16, byteRGBRaster.getWidth() * xSubsampling - xStart);

        for (int y = 0; y < yEnd; y += ySubsampling) {
            int dstY = (yStart + y) / ySubsampling;
            if (dstY < 0) {
                continue;
            }

            for (int x = 0; x < xEnd; x += xSubsampling) {
                int dstX = (xStart + x) / xSubsampling;
                if (dstX < 0) {
                    continue;
                }

                yuv[0] = (byte) macroBlock.getSubBlock(SubBlock.Plane.Y1, x / 4, y / 4).getDest()[x % 4][y % 4];
                yuv[1] = (byte) macroBlock.getSubBlock(SubBlock.Plane.U, (x / 2) / 4, (y / 2) / 4).getDest()[(x / 2) % 4][(y / 2) % 4];
                yuv[2] = (byte) macroBlock.getSubBlock(SubBlock.Plane.V, (x / 2) / 4, (y / 2) / 4).getDest()[(x / 2) % 4][(y / 2) % 4];

                // TODO: Consider doing YCbCr -> RGB in reader instead, or pass a flag to allow readRaster reading direct YUV/YCbCr values
                convertYCbCr2RGB(yuv, rgb, 0);
                byteRGBRaster.setDataElements(dstX, dstY, rgb);
//                 byteRGBRaster.setDataElements(dstX, dstY, yuv);
            }
        }
    }
}

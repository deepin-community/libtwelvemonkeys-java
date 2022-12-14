/*
 * Copyright (c) 2008, Harald Kuhr
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
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
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.twelvemonkeys.imageio.plugins.pict;

import javax.imageio.IIOException;
import java.awt.*;
import java.awt.color.ColorSpace;
import java.awt.image.*;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

import static com.twelvemonkeys.imageio.plugins.pict.QuickTime.ImageDesc;
import static com.twelvemonkeys.imageio.plugins.pict.QuickTime.VENDOR_APPLE;

/**
 * QTRAWDecompressor
 *
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 * @author last modified by $Author: haraldk$
 * @version $Id: QTRAWDecompressor.java,v 1.0 Feb 16, 2009 9:29:18 PM haraldk Exp$
 */
final class QTRAWDecompressor extends QTDecompressor {
    // TODO: Create a RAWImageReader for ImageIO to delegate to?
    //  - Would have to require a parameter controlling bit depth and pixel layout
    //  - Have a look at com.sun.media.imageio.stream.RawImageInputStream...
    // TODO: Support different bit depths

    public boolean canDecompress(final ImageDesc description) {
        return VENDOR_APPLE.equals(description.compressorVendor)
                && "raw ".equals(description.compressorIdentifer)
                && (description.depth == 24 || description.depth == 32 || description.depth == 40);
    }

    public BufferedImage decompress(final ImageDesc description, final InputStream stream) throws IOException {
        byte[] data = new byte[description.dataSize];

        try (DataInputStream dataStream = new DataInputStream(stream)) {
            dataStream.readFully(data, 0, description.dataSize);
        }

        DataBuffer buffer = new DataBufferByte(data, data.length);

        WritableRaster raster;

        // TODO: Depth parameter can be 1-32 (color) or 33-40 (gray scale)
        switch (description.depth) {
            case 40: // 8 bit gray (untested)
                raster = Raster.createInterleavedRaster(
                        buffer,
                        description.width, description.height,
                        description.width, 1,
                        new int[] {0},
                        null
                );
                break;
            case 24: // 24 bit RGB
                raster = Raster.createInterleavedRaster(
                        buffer,
                        description.width, description.height,
                        description.width * 3, 3,
                        new int[] {0, 1, 2},
                        null
                );
                break;
            case 32: // 32 bit ARGB
                // WORKAROUND: There is a bug in the way Java 2D interprets the band offsets in
                // Raster.createInterleavedRaster (see below) before Java 6. So, instead of
                // passing a correct offset array below, we swap channel 1 & 3 to make it ABGR...
                for (int y = 0; y < description.height; y++) {
                    for (int x = 0; x < description.width; x++) {
                        int offset = 4 * y * description.width + x * 4;
                        byte temp = data[offset + 1];
                        data[offset + 1] = data[offset + 3];
                        data[offset + 3] = temp;
                    }
                }

                raster = Raster.createInterleavedRaster(
                        buffer,
                        description.width, description.height,
                        description.width * 4, 4,
                        new int[] {3, 2, 1, 0}, // B & R mixed up. {1, 2, 3, 0} is correct
                        null
                );
                break;
            default:
                throw new IIOException("Unsupported QuickTime RAW depth: " + description.depth);
        }

        ColorModel cm = new ComponentColorModel(
                description.depth <= 32 ? ColorSpace.getInstance(ColorSpace.CS_sRGB) : ColorSpace.getInstance(ColorSpace.CS_GRAY),
                description.depth == 32,
                false,
                description.depth == 32 ? Transparency.TRANSLUCENT : Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE
        );

        return new BufferedImage(cm, raster, cm.isAlphaPremultiplied(), null);
    }
}

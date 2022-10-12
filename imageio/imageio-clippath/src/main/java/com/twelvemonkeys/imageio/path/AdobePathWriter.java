/*
 * Copyright (c) 2020 Harald Kuhr
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

package com.twelvemonkeys.imageio.path;

import com.twelvemonkeys.imageio.metadata.psd.PSD;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.twelvemonkeys.imageio.path.AdobePathReader.DEBUG;
import static com.twelvemonkeys.imageio.path.AdobePathSegment.*;
import static com.twelvemonkeys.lang.Validate.isTrue;
import static com.twelvemonkeys.lang.Validate.notNull;

/**
 * Writes a {@code Shape} object to an Adobe Photoshop Path or Path resource.
 *
 * @see <a href="http://www.adobe.com/devnet-apps/photoshop/fileformatashtml/#50577409_17587">Adobe Photoshop Path resource format</a>
 * @author <a href="mailto:harald.kuhr@gmail.com">Harald Kuhr</a>
 */
public final class AdobePathWriter {

    // TODO: Might need to get hold of more real Photoshop samples to tune this threshold...
    private static final double COLLINEARITY_THRESHOLD = 0.00000001;

    private final List<AdobePathSegment> segments;

    /**
     * Creates an AdobePathWriter for the given path.
     * <p>
     * NOTE: Photoshop paths are stored with the coordinates
     * (0,0) representing the top left corner of the image,
     * and (1,1) representing the bottom right corner,
     * regardless of image dimensions.
     * </p>
     *
     * @param path A {@code Shape} instance that has {@link Path2D#WIND_EVEN_ODD WIND_EVEN_ODD} rule,
     *             is contained within the rectangle [x=0.0,y=0.0,w=1.0,h=1.0], and is closed.
     * @throws IllegalArgumentException if {@code path} is {@code null},
     *                                  the paths winding rule is not @link Path2D#WIND_EVEN_ODD} or
     *                                  the paths bounding box is outside [x=0.0,y=0.0,w=1.0,h=1.0] or
     *                                  the path is not closed.
     */
    public AdobePathWriter(final Shape path) {
        notNull(path, "path");
        isTrue(new Rectangle(0, 0, 1, 1).contains(path.getBounds2D()), path.getBounds2D(), "Path bounds must be within [x=0,y=0,w=1,h=1]: %s");

        segments = pathToSegments(path.getPathIterator(null));
    }

    // TODO: Look at the API so that conversion both ways are aligned. The read part builds a path from List<List<AdobePathSegment>...
    private static List<AdobePathSegment> pathToSegments(final PathIterator pathIterator) {
        // TODO: Test if PS really ignores winding rule as documented... Otherwise we could support writing non-zero too.
        isTrue(pathIterator.getWindingRule() == Path2D.WIND_EVEN_ODD, pathIterator.getWindingRule(), "Only even/odd winding rule supported: %d");

        double[] coords = new double[6];
        AdobePathSegment prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, 0, 0, 0, 0, 0, 0);

        List<AdobePathSegment> subpath = new ArrayList<>();
        List<AdobePathSegment> segments = new ArrayList<>();
        segments.add(new AdobePathSegment(PATH_FILL_RULE_RECORD, 0));
        segments.add(new AdobePathSegment(INITIAL_FILL_RULE_RECORD, 0));

        while (!pathIterator.isDone()) {
            int segmentType = pathIterator.currentSegment(coords);

            if (DEBUG) {
                System.out.println("segmentType: " + segmentType);
                System.out.println("coords: " + Arrays.toString(coords));
            }

            // We write collinear points as linked segments
            boolean collinear = isCollinear(prev.cppx, prev.cppy, prev.apx, prev.apy, coords[0], coords[1]);

            switch (segmentType) {
                case PathIterator.SEG_MOVETO:
                    // TODO: What if we didn't close before the moveto? Start new segment here?

                    // Dummy starting point, will be updated later
                    prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, 0, 0, coords[1], coords[0], 0, 0);
                    break;

                case PathIterator.SEG_LINETO:
                    subpath.add(new AdobePathSegment(collinear ? CLOSED_SUBPATH_BEZIER_LINKED : CLOSED_SUBPATH_BEZIER_UNLINKED, prev.cppy, prev.cppx, prev.apy, prev.apx, coords[1], coords[0]));
                    prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, coords[1], coords[0], coords[1], coords[0], 0, 0);
                    break;

                case PathIterator.SEG_QUADTO:
                    subpath.add(new AdobePathSegment(collinear ? CLOSED_SUBPATH_BEZIER_LINKED : CLOSED_SUBPATH_BEZIER_UNLINKED, prev.cppy, prev.cppx, prev.apy, prev.apx, coords[1], coords[0]));
                    prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, coords[3], coords[2], coords[3], coords[2], 0, 0);
                    break;

                case PathIterator.SEG_CUBICTO:
                    subpath.add(new AdobePathSegment(collinear ? CLOSED_SUBPATH_BEZIER_LINKED : CLOSED_SUBPATH_BEZIER_UNLINKED, prev.cppy, prev.cppx, prev.apy, prev.apx, coords[1], coords[0]));
                    prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, coords[3], coords[2], coords[5], coords[4], 0, 0);
                    break;

                case PathIterator.SEG_CLOSE:
                    AdobePathSegment initial = subpath.get(0);

                    if (initial.apx != prev.apx || initial.apy != prev.apy) {
                        // Line back to initial if last anchor point does not equal initial anchor
                        collinear = isCollinear(prev.cppx, prev.cppy, initial.apx, initial.apy, initial.apx, initial.apy);
                        subpath.add(new AdobePathSegment(collinear ? CLOSED_SUBPATH_BEZIER_LINKED : CLOSED_SUBPATH_BEZIER_UNLINKED, prev.cppy, prev.cppx, prev.apy, prev.apx, initial.apy, initial.apx));
                        prev = new AdobePathSegment(CLOSED_SUBPATH_BEZIER_LINKED, initial.apy, initial.apx, initial.apy, initial.apx, 0, 0);
                    }

                    close(initial, prev, subpath, segments);
                    subpath.clear();

                    break;
            }

            pathIterator.next();
        }

        // If subpath is not empty at this point, there was no close segment...
        // Wrap up if coordinates match, otherwise throw exception
        if (!subpath.isEmpty()) {
            AdobePathSegment initial = subpath.get(0);

            if (initial.apx != prev.apx || initial.apy != prev.apy) {
                throw new IllegalArgumentException("Path must be closed");
            }

            close(initial, prev, subpath, segments);
        }

        return segments;
    }

    private static void close(AdobePathSegment initial, AdobePathSegment prev, List<AdobePathSegment> subpath, List<AdobePathSegment> segments) {
        // Replace initial point.
        boolean collinear = isCollinear(prev.cppx, prev.cppy, initial.apx, initial.apy, initial.cplx, initial.cply);
        subpath.set(0, new AdobePathSegment(collinear ? CLOSED_SUBPATH_BEZIER_LINKED : CLOSED_SUBPATH_BEZIER_UNLINKED, prev.cppy, prev.cppx, initial.apy, initial.apx, initial.cply, initial.cplx));

        // Add to full path
        segments.add(new AdobePathSegment(CLOSED_SUBPATH_LENGTH_RECORD, subpath.size()));
        segments.addAll(subpath);
    }

    private static boolean isCollinear(double x1, double y1, double x2, double y2, double x3, double y3) {
        // Photoshop seems to write as linked if all points are the same....
        return (x1 == x2 && x2 == x3 && y1 == y2 && y2 == y3) ||
                (x1 != x2 || y1 != y2) && (x2 != x3 || y2 != y3) &&
                 Math.abs(x1 * (y2 - y3) + x2 * (y3 - y1) + x3 * (y1 - y2)) <= COLLINEARITY_THRESHOLD; // With some slack...

    }

    /**
     * Writes the path as a complete Adobe Photoshop clipping path resource to the given stream.
     *
     * @param resourceId the resource id, typically {@link PSD#RES_CLIPPING_PATH} (0x07D0).
     * @param output the stream to write to.
     * @throws IOException if an I/O exception happens during writing.
     */
    public void writePathResource(int resourceId, final DataOutput output) throws IOException {
        output.writeInt(PSD.RESOURCE_TYPE);
        output.writeShort(resourceId);
        output.writeShort(0); // Path name (Pascal string) empty + pad
        output.writeInt(segments.size() * 26); // Resource size

        writePath(output);
    }

    /**
     * Writes the path as a set of Adobe Photoshop path segments to the given stream.
     *
     * @param output the stream to write to.
     * @throws IOException if an I/O exception happens during writing.
     */
    public void writePath(final DataOutput output) throws IOException {
        if (DEBUG) {
            System.out.println("segments: " + segments.size());
            System.out.println(segments);
        }

        for (AdobePathSegment segment : segments) {
            switch (segment.selector) {
                case PATH_FILL_RULE_RECORD:
                case INITIAL_FILL_RULE_RECORD:
                    // The first 26-byte path record contains a selector value of 6, path fill rule record.
                    // The remaining 24 bytes of the first record are zeroes. Paths use even/odd ruling.
                    output.writeShort(segment.selector);
                    output.write(new byte[24]);
                    break;
                case OPEN_SUBPATH_LENGTH_RECORD:
                case CLOSED_SUBPATH_LENGTH_RECORD:
                    output.writeShort(segment.selector);
                    output.writeShort(segment.lengthOrRule); // Subpath length
                    output.write(new byte[22]);
                    break;
                default:
                    output.writeShort(segment.selector);
                    output.writeInt(toFixedPoint(segment.cppy));
                    output.writeInt(toFixedPoint(segment.cppx));
                    output.writeInt(toFixedPoint(segment.apy));
                    output.writeInt(toFixedPoint(segment.apx));
                    output.writeInt(toFixedPoint(segment.cply));
                    output.writeInt(toFixedPoint(segment.cplx));
                    break;
            }
        }
    }

    /**
     * Transforms the path to a byte array, containing a complete Adobe Photoshop path resource.
     *
     * @param resourceId the resource id, typically {@link PSD#RES_CLIPPING_PATH} (0x07D0).
     * @return a new byte array, containing the clipping path resource.
     */
    public byte[] writePathResource(int resourceId) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream stream = new DataOutputStream(bytes)) {
            writePathResource(resourceId, stream);
        }
        catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream threw IOException", e);
        }

        return bytes.toByteArray();
    }

    /**
     * Transforms the path to a byte array, containing a set of Adobe Photoshop path segments.
     *
     * @return a new byte array, containing the path segments.
     */
    public byte[] writePath() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (DataOutputStream stream = new DataOutputStream(bytes)) {
            writePath(stream);
        }
        catch (IOException e) {
            throw new AssertionError("ByteArrayOutputStream threw IOException", e);
        }

        return bytes.toByteArray();
    }
}

/*
 * Copyright (C) 2014-2015 OpenKeeper
 *
 * OpenKeeper is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OpenKeeper is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OpenKeeper.  If not, see <http://www.gnu.org/licenses/>.
 */
package toniarts.openkeeper.tools.convert.str;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import toniarts.openkeeper.tools.convert.ConversionUtils;

/**
 * Reads the Dungeon Keeper 2 STR files<br>
 * Converted to JAVA from C code, C code by:
 * <li>Tomasz Lis</li>
 *
 * @author Toni Helenius <helenius.toni@gmail.com>
 */
public class StrFile {

    private static final String STR_HEADER_IDENTIFIER = "BFST";
    private static final String CODEPAGE_HEADER_IDENTIFIER = "BFMU";
    private static final int STR_HEADER_SIZE = 12;
    // Codepage chunk types
    private static final int CHUNK_TYPE_END = 0;
    private static final int CHUNK_TYPE_STRING = 1;
    private static final int CHUNK_TYPE_PARAM = 2;
    //
    private static final Logger logger = Logger.getLogger(StrFile.class.getName());
    private final CharBuffer codePage;
    private final int fileId;
    private final LinkedHashMap<Integer, String> entries;

    /**
     * Constructs a new STR file reader<br>
     * Reads the STR file structure
     *
     * @param file the str file to read
     */
    public StrFile(File file) {
        this(readCodePage(file), file);
    }

    /**
     * Constructs a new STR file reader using given code page. Particularly
     * useful for batch runs, no need to read the code page all over again<br>
     * Reads the STR file structure
     *
     * @param codePage the code page
     * @param file the str file to read
     */
    public StrFile(CharBuffer codePage, File file) {
        this.codePage = codePage;

        // Read the file
        try (RandomAccessFile rawStr = new RandomAccessFile(file, "r")) {

            // Check the header
            byte[] header = new byte[4];
            rawStr.read(header);
            if (!STR_HEADER_IDENTIFIER.equals(ConversionUtils.toString(header))) {
                throw new RuntimeException("Header should be " + STR_HEADER_IDENTIFIER + " and it was " + header + "! Cancelling!");
            }

            // Header... 12 bytes, must be added to offsets
            fileId = ConversionUtils.readUnsignedInteger(rawStr);
            int offsetsCount = ConversionUtils.readUnsignedInteger(rawStr);

            // Read the offsets
            List<Integer> offsets = new ArrayList<>(offsetsCount);
            for (int i = 0; i < offsetsCount; i++) {
                offsets.add(ConversionUtils.readUnsignedInteger(rawStr));
            }

            // Make a copy because offsets in some languages (like german) are not sorted!
            List<Integer> offsetsCopy = new ArrayList<>(offsets);
            Collections.sort(offsetsCopy);

            // Decode the entries
            entries = new LinkedHashMap<>(offsetsCount);
            for (int i = 0; i < offsetsCount; i++) {
                // Seek to the data and read it
                rawStr.seek(offsets.get(i) + STR_HEADER_SIZE);
                int j = Collections.binarySearch(offsetsCopy, offsets.get(i));
                int dataLength = (int) (j < offsetsCopy.size() - 1 ? offsetsCopy.get(j + 1) - offsets.get(i) : rawStr.length() - offsets.get(i) - STR_HEADER_SIZE);

                byte[] data = new byte[dataLength];
                int dataRead = rawStr.read(data);
                if (dataRead < dataLength) {
                    logger.log(Level.WARNING, "Entry {0} was supposed to be {1} but only {2} could be read!", new Object[]{i, dataLength, dataRead});
                }

                // Encode the string
                String entry = decodeEntry(data);
                if (entry == null) {
                    throw new RuntimeException("Failed to encode entry #" + i + "!");
                }
                entries.put(i, entry);
            }
        } catch (IOException e) {

            // Fug
            throw new RuntimeException("Failed to read the file " + file + "!", e);
        }
    }

    /**
     * Reads the code page file "MBToUni.dat". It is assumed to be in the same
     * directory than the file
     *
     * @param file STR file
     * @return code page as char buffer
     * @throws RuntimeException may fail miserably
     */
    private static CharBuffer readCodePage(File file) throws RuntimeException {

        // We also need the codepage, assume it is in the same directory
        final File mbToUniFile = new File(file.getParent().concat(File.separator).concat("MBToUni.dat"));
        try (RandomAccessFile rawCodepage = new RandomAccessFile(mbToUniFile, "r")) {
            CharBuffer buffer;

            // Check the header
            byte[] header = new byte[4];
            rawCodepage.read(header);
            if (!CODEPAGE_HEADER_IDENTIFIER.equals(ConversionUtils.toString(header))) {
                throw new RuntimeException("Header should be " + CODEPAGE_HEADER_IDENTIFIER + " and it was " + header + "! Cancelling!");
            }
            rawCodepage.skipBytes(2); // Don't know what is here
            byte[] data = new byte[(int) (rawCodepage.length() - rawCodepage.getFilePointer())];
            rawCodepage.read(data);

            // Put the code page to byte buffer for fast access, it is a small file
            ByteBuffer buf = ByteBuffer.wrap(data).asReadOnlyBuffer();
            buf.order(ByteOrder.LITTLE_ENDIAN);
            buffer = buf.asCharBuffer();
            return buffer;
        } catch (IOException e) {

            // Fug
            throw new RuntimeException("Failed to read the file " + mbToUniFile + "!", e);
        }
    }

    /**
     * Decodes one entry in STR file
     *
     * @param data the entry bytes
     * @return returns null if error occured, otherwise the decoded string
     */
    private String decodeEntry(final byte[] data) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(data);
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN);
        StringBuilder buffer = new StringBuilder(data.length);

        // Loop through the bytes
        int chunkType;
        do {

            // Read chunk
            chunkType = byteBuffer.getInt();
            int chunkLength = (chunkType >> 8);
            chunkType &= 0xff;

            // Check the type
            switch (chunkType) {
                case CHUNK_TYPE_END: { // End
                    if (chunkLength != 0) {
                        logger.severe("End chunk has non-zero length!");
                        return null;
                    }
                    break;
                }
                case CHUNK_TYPE_PARAM: { // Param
                    buffer.append('%');
                    int val = (chunkLength + 1) / 10;
                    if (val > 0) {
                        buffer.append(val);
                    }
                    val = (chunkLength + 1) % 10;
                    buffer.append(val);
                    break;
                }
                case CHUNK_TYPE_STRING: { // String
                    if (chunkLength > byteBuffer.remaining()) {
                        logger.severe("Chunk length exceeds the remaining bytes length!");
                        return null;
                    }

                    // Decode the chunk
                    byte[] chunk = new byte[chunkLength];
                    byteBuffer.get(chunk);
                    buffer.append(decodeChunk(chunk));
                    break;
                }
                default: {
                    logger.severe("Invalid chunk type!");
                    return null;
                }
            }

            // Position needs to be divisible by 4
            if (byteBuffer.position() % 4 != 0) {
                byteBuffer.position(byteBuffer.position() + (4 - byteBuffer.position() % 4));
            }
        } while (chunkType != CHUNK_TYPE_END && byteBuffer.hasRemaining());

        // Return the string
        return buffer.toString();
    }

    /**
     * Decodes one string chunk inside of an STR entry
     *
     * @param chunk chuck data
     * @return decoded string
     */
    private String decodeChunk(final byte[] chunk) {

        // Go through each byte
        StringBuilder buffer = new StringBuilder(chunk.length);
        int codePageIndex = 0;
        for (byte b : chunk) {
            short chr = ConversionUtils.toUnsignedByte(b);
            char character;
            if (chr == 0xff) {
                codePageIndex += 254;
                continue;
            }

            // Read the character from the code page
            codePageIndex += chr;
            if (codePageIndex < codePage.limit()) {
                character = codePage.charAt(codePageIndex);
            } else {
                character = '_';
            }

            // Escapes
            if (character == '%') {
                buffer.append("%");
            } else if ((character == '\\') || (character == '\n') || (character == '\t')) {
                buffer.append("\\");
                switch (character) {
                    case '\\':
                        break;
                    case '\n':
                        character = 'n';
                        break;
                    case '\t':
                        character = 't';
                        break;
                }
            }

            // Add the character to the buffer
            buffer.append(character);

            // Reset
            codePageIndex = 0;
        }

        // Return the decoded string
        return buffer.toString();
    }

    /**
     * Get the string entries<br>
     * The entries are returned in order (by the id) for your inconvenience
     *
     * @return set of entries
     */
    public Set<Entry<Integer, String>> getEntriesAsSet() {
        return entries.entrySet();
    }

    /**
     * Get the mapped entries<br>
     * The entries are returned in order (by the id) for your inconvenience
     *
     * @return the entries
     */
    public LinkedHashMap<Integer, String> getEntries() {
        return entries;
    }

    /**
     * Get the used code page for reuse in batch runs
     *
     * @return the code page
     */
    public CharBuffer getCodePage() {
        return codePage;
    }
}

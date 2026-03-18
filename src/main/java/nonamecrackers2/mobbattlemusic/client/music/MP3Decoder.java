package nonamecrackers2.mobbattlemusic.client.music;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

/**
 * Decodes MP3 files to PCM audio data
 */
public class MP3Decoder {
    private static final Logger LOGGER = LogManager.getLogger("mobbattlemusic/MP3Decoder");
    
    /**
     * Decode MP3 data to PCM
     * @param mp3Data The MP3 file data
     * @return AudioData containing PCM data
     * @throws DecoderException If decoding fails
     */
    public AudioData decode(byte[] mp3Data) throws DecoderException {
        if (!isValidMP3(mp3Data)) {
            throw new DecoderException("Invalid MP3 file: missing or invalid header");
        }
        
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(mp3Data);
            Bitstream bitstream = new Bitstream(inputStream);
            Decoder decoder = new Decoder();
            
            ByteArrayOutputStream pcmOutput = new ByteArrayOutputStream();
            int sampleRate = 0;
            int channels = 0;
            
            Header header;
            while ((header = bitstream.readFrame()) != null) {
                try {
                    SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    
                    if (sampleRate == 0) {
                        sampleRate = output.getSampleFrequency();
                        channels = output.getChannelCount();
                        LOGGER.debug("MP3 format: {}Hz, {} channels", sampleRate, channels);
                    }
                    
                    // Convert samples to bytes (16-bit PCM)
                    short[] samples = output.getBuffer();
                    int length = output.getBufferLength();
                    
                    for (int i = 0; i < length; i++) {
                        short sample = samples[i];
                        pcmOutput.write(sample & 0xFF);
                        pcmOutput.write((sample >> 8) & 0xFF);
                    }
                    
                    bitstream.closeFrame();
                } catch (Exception e) {
                    LOGGER.warn("Error decoding MP3 frame: {}", e.getMessage());
                    bitstream.closeFrame();
                }
            }
            
            bitstream.close();
            
            byte[] pcmData = pcmOutput.toByteArray();
            LOGGER.info("Decoded MP3: {} bytes PCM data, {}Hz, {} channels", 
                pcmData.length, sampleRate, channels);
            
            return new AudioData(pcmData, sampleRate, channels, 16);
            
        } catch (BitstreamException e) {
            throw new DecoderException("Failed to decode MP3: " + e.getMessage(), e);
        }
    }
    
    /**
     * Validate if the data is a valid MP3 file
     * @param data The data to validate
     * @return true if valid MP3
     */
    public boolean isValidMP3(byte[] data) {
        if (data == null || data.length < 4) {
            return false;
        }
        
        // Check for MP3 frame sync (11 bits set to 1)
        // MP3 frames start with 0xFF 0xFB, 0xFF 0xFA, or 0xFF 0xF3, etc.
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            return true;
        }
        
        // Check for ID3 tag
        if (data.length >= 3 && 
            data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
            return true;
        }
        
        return false;
    }
    
    /**
     * Exception thrown when MP3 decoding fails
     */
    public static class DecoderException extends Exception {
        public DecoderException(String message) {
            super(message);
        }
        
        public DecoderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

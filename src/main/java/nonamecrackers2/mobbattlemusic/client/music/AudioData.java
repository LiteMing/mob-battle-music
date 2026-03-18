package nonamecrackers2.mobbattlemusic.client.music;

/**
 * Represents PCM audio data
 */
public class AudioData {
    private final byte[] pcmData;
    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    
    public AudioData(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        this.pcmData = pcmData;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bitsPerSample = bitsPerSample;
    }
    
    public byte[] getPcmData() {
        return pcmData;
    }
    
    public int getSampleRate() {
        return sampleRate;
    }
    
    public int getChannels() {
        return channels;
    }
    
    public int getBitsPerSample() {
        return bitsPerSample;
    }
    
    public int getDataLength() {
        return pcmData.length;
    }
    
    @Override
    public String toString() {
        return String.format("AudioData[sampleRate=%d, channels=%d, bits=%d, length=%d]",
            sampleRate, channels, bitsPerSample, pcmData.length);
    }
}

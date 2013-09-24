package net.sf.fmj.media.multiplexer.audio;

import javax.media.Format;
import javax.media.format.AudioFormat;
import javax.media.protocol.ContentDescriptor;
import javax.media.protocol.FileTypeDescriptor;

import net.sf.fmj.media.codec.JavaSoundCodec;
import net.sf.fmj.media.multiplexer.*;
import net.sf.fmj.media.renderer.audio.JavaSoundUtils;

public class WAVMux extends BasicMux
{
    private static final int RIFF_CHUNK_SIZE_IDX = 4;

    private static final int WAV_DATA_CHUNK_SIZE_IDX = 40;

    private static final int WAV_FILE_HEADER_SIZE = 44;

    private int mBytesWritten = 0;

    public WAVMux()
    {
        supportedInputs = new Format[1];
        supportedInputs[0] = new AudioFormat(
                AudioFormat.LINEAR,
                /* sampleRate */ Format.NOT_SPECIFIED,
                16,
                /* channels */ Format.NOT_SPECIFIED,
                AudioFormat.LITTLE_ENDIAN,
                AudioFormat.SIGNED,
                /* frameSizeInBits */ Format.NOT_SPECIFIED,
                /* frameRate */ Format.NOT_SPECIFIED,
                Format.byteArray);
        supportedOutputs = new ContentDescriptor[1];
        supportedOutputs[0] = new FileTypeDescriptor(
                FileTypeDescriptor.WAVE);
    }

    public String getName()
    {
        return "WAV Audio Multiplexer";
    }

    @Override
    public Format setInputFormat(Format format, int trackID)
    {
        final AudioFormat af = (AudioFormat) format;
        if (af.getSampleSizeInBits() == 8
                && af.getSigned() == AudioFormat.SIGNED)
            return null; // 8-bit is always unsigned for Wav.

        if (af.getSampleSizeInBits() == 16
                && af.getSigned() == AudioFormat.UNSIGNED)
            return null; // 16-bit is always signed for Wav.

        inputs[0] = format;
        return format;
    }

    @Override
    protected boolean needsSeekable()
    {
        // In order to set the Wav header's length field correctly, we need to
        // be able to go back and amend the value once the entire file has been
        // written.
        return true;
    }

    @Override
    protected int write(byte[] data, int offset, int length)
    {
        if (source == null || !source.isConnected())
            return length;
        if (length > 0)
        {
            filePointer += length;
            if (filePointer > fileSize)
                fileSize = filePointer;
            if (fileSizeLimit > 0 && fileSize >= fileSizeLimit)
                fileSizeLimitReached = true;
        }

        int bytesWritten = stream.write(data, offset, length);
        mBytesWritten += bytesWritten;

        return bytesWritten;
    }

    @Override
    protected void writeHeader()
    {
        // Hack - we assume all channels are in the same format.
        javax.sound.sampled.AudioFormat javaAudioFormat =
                JavaSoundUtils.convertFormat((AudioFormat) inputs[0]);
        byte[] wavHeader = JavaSoundCodec.createWavHeader(javaAudioFormat);

        stream.write(wavHeader, 0, wavHeader.length);
    }

    @Override
    protected void writeFooter()
    {
        // Riff file length excludes the RIFF ID word and the WAV ID word.
        int riffFileLength = mBytesWritten + WAV_FILE_HEADER_SIZE - 8;
        seek(RIFF_CHUNK_SIZE_IDX);
        bufWriteIntLittleEndian(riffFileLength);
        bufFlush();

        seek(WAV_DATA_CHUNK_SIZE_IDX);
        bufWriteIntLittleEndian(mBytesWritten);
        bufFlush();
    }
}

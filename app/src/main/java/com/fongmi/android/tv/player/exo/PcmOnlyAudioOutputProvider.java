package com.fongmi.android.tv.player.exo;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.exoplayer.audio.AudioOutput;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioOutputProvider.ConfigurationException;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig;
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport;
import androidx.media3.exoplayer.audio.AudioOutputProvider.InitializationException;
import androidx.media3.exoplayer.audio.AudioOutputProvider.Listener;
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig;

/** Drops compressed formats so AudioTrack cannot open AC3/DTS/E-AC3 as passthrough. */
final class PcmOnlyAudioOutputProvider implements AudioOutputProvider {

    private final AudioOutputProvider delegate;

    PcmOnlyAudioOutputProvider(AudioOutputProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public FormatSupport getFormatSupport(FormatConfig formatConfig) {
        Format format = formatConfig.format;
        if (format.sampleMimeType != null && !MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)) {
            return FormatSupport.UNSUPPORTED;
        }
        return delegate.getFormatSupport(formatConfig);
    }

    @Override
    public OutputConfig getOutputConfig(FormatConfig formatConfig) throws ConfigurationException {
        return delegate.getOutputConfig(formatConfig);
    }

    @Override
    public AudioOutput getAudioOutput(OutputConfig config) throws InitializationException {
        return delegate.getAudioOutput(config);
    }

    @Override
    public void addListener(Listener listener) {
        delegate.addListener(listener);
    }

    @Override
    public void removeListener(Listener listener) {
        delegate.removeListener(listener);
    }

    @Override
    public void setClock(Clock clock) {
        delegate.setClock(clock);
    }

    @Override
    public void release() {
        delegate.release();
    }
}

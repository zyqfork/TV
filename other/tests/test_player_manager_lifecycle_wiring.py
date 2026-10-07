"""Source wiring checks for PlayerManager lifecycle cancellation.
This intentionally is NOT a compiled PlayerManager/Android lifecycle runtime test.
Engine/recovery runtime doubles are in test_player_pending_start.py.
"""
from pathlib import Path
import argparse
import re

ROOT = Path(__file__).resolve().parents[2]


def body(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return re.sub(r'\s+', '', source[opening + 1:end - 1])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    args = parser.parse_args()
    source = (args.source_root / 'app/src/main/java/com/fongmi/android/tv/player/PlayerManager.java').read_text(encoding='utf-8')
    cancel = 'App.removeCallbacks(runnable,firstFrameRunnable,sourceRetryRunnable);'
    stopped = body(source, 'public void stop()')
    assert cancel in stopped, 'stop must cancel all watchdog/retry schedules'
    assert stopped.index(cancel) < stopped.index('engine.stop();'), 'invalidate schedules before stopping engine'
    cleared = body(source, 'public void clearMediaItems()')
    assert 'stop();' in cleared and cleared.index('stop();') < cleared.index('player.clearMediaItems();'), 'clear must invalidate pending engine/parser work'
    started = body(source, 'public void start(PlaySpec spec, long timeout, long startPositionMs)')
    assert 'stopParse();' in started, 'direct start must supersede old parser'
    parsed = body(source, 'public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long startPositionMs)')
    assert 'stop();' in parsed and parsed.index('stop();') < parsed.index('ParseJob.create('), 'new parser must supersede old engine work and schedules'
    prepared = body(source, 'private void setMediaItem(long timeout, long startPositionMs, boolean playWhenReady)')
    assert cancel in prepared, 'new start must cancel an obsolete delayed source retry'
    assert 'sourceRetry=0' not in prepared, 'internal restart must not replenish retry budget'
    assert 'engine.start(spec,startPositionMs,playWhenReady);' in prepared
    for signature in ('public void refreshAudioSetting()', 'public void refreshVideoSetting()', 'private void switchDecode(boolean persist, boolean freshAttempt)', 'private boolean fallbackMpvToExo()'):
        code = body(source, signature)
        assert 'booleanplayWhenReady=player.getPlayWhenReady();' in code, signature
        if 'engine.rebuild()' in code:
            assert code.index('player.getPlayWhenReady()') < code.index('engine.rebuild()'), signature
            assert 'startCurrent(position,playWhenReady);' in code, signature
    sub = body(source, 'public void setSub(Sub sub)')
    assert 'play();' not in sub, 'subtitle import must not resume playback'
    restarting = body(source, 'private void startCurrent(long startPositionMs)')
    assert 'player.getPlayWhenReady()' in restarting
    resolved = body(source, 'public void onParseSuccess(Map<String, String> headers, String url, String from)')
    assert 'headers=ParsePlaybackState.playbackHeaders(headers);' in resolved, 'resolver headers must not be mutated'
    assert 'parsePlaybackState.complete()' in resolved and 'setMediaItem(Constant.TIMEOUT_PLAY,pendingStartPositionMs,playWhenReady);' in resolved, 'resolution must use latest pending intent'
    assert 'parsePlaybackState.begin();' in parsed and 'player.setPlayWhenReady(true);' in parsed, 'fresh parse must publish initial autoplay intent'
    for signature, intent in (('public void play()', 'true'), ('public void pause()', 'false')):
        assert f'parsePlaybackState.setPlayWhenReady({intent});' in body(source, signature)
    assert 'parsePlaybackState.cancel();' in body(source, 'private void stopParse()')
    service = (args.source_root / 'app/src/main/java/com/fongmi/android/tv/service/PlaybackService.java').read_text(encoding='utf-8')
    wrapped = body(service, 'private ForwardingPlayer wrap(Player base)')
    assert 'PlaybackService.this.player.play();' in body(wrapped, 'publicvoidplay()')
    assert 'PlaybackService.this.player.pause();' in body(wrapped, 'publicvoidpause()')
    ready = body(wrapped, 'publicvoidsetPlayWhenReady(booleanplayWhenReady)')
    assert 'PlaybackService.this.player.play();' in ready and 'PlaybackService.this.player.pause();' in ready
    assert 'player.isParsing()' in body(service, 'private boolean shouldKeepAlive()')
    assert '!player.isParsing()' in body(service, 'public void dispatchStop()')
    error = body(service, 'public void onError(String msg)')
    assert 'if(!shouldKeepAlive())tryShutdown();' in error, 'idle unbound parser failure must not pin service'
    cleared = body(service, 'private void stopAndClear()')
    assert 'player.clearMediaItems();' in cleared and 'player.stop();' not in cleared, 'clearing must not duplicate native stop'
    print('PASS Manager/MediaSession source wiring: cancellation/budgets, rebuild intent, parser/controller intent and immutable header isolation')


if __name__ == '__main__':
    main()

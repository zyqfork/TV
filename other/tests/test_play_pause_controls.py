"""Static TV control wiring contracts; does not substitute for device key testing."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = '{http://schemas.android.com/apk/res/android}'

def body(text, method):
    start = text.index(method)
    first = text.index('{', start)
    depth = 1
    end = first + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[first + 1:end - 1]

for page, first, last in [('cast', 'player', 'video'), ('vod', 'next', 'ending')]:
    path = f'app/src/leanback/res/layout/view_control_{page}_action.xml'
    root = ET.fromstring((ROOT / path).read_text(encoding='utf8'))
    nodes = {n.get(ANDROID + 'id').split('/')[-1]: n for n in root.iter() if n.get(ANDROID + 'id')}
    old_ids = set(('player decode reset speed scale text audio video' if page == 'cast' else
                   'next prev parse player decode reset replay repeat speed scale text audio video '
                   'danmaku edition chapter opening ending').split())
    assert set(nodes) == old_ids | {'play_pause'}, (page, 'existing controls removed')
    button = nodes['play_pause']
    assert button.get('style') == '@style/Control' and button.get(ANDROID + 'text') == '@string/play'
    assert button.get(ANDROID + 'nextFocusRight') == '@id/' + first
    assert nodes[first].get(ANDROID + 'nextFocusLeft') == '@id/play_pause'
    assert nodes[last].get(ANDROID + 'nextFocusRight') == '@id/play_pause'

for name in ['CastActivity', 'VideoActivity']:
    java = (ROOT / f'app/src/leanback/java/com/fongmi/android/tv/ui/activity/{name}.java').read_text(encoding='utf8')
    assert 'action.playPause.setOnClickListener(view -> onKeyCenter())' in java
    assert 'setSeekNextFocusDown(R.id.play_pause)' in java
    assert 'getPlayWhenReady() ? R.string.pause : R.string.play' in body(java, 'private void setPlayPauseText()')
    toggle = body(java, 'public void onKeyCenter()')
    assert 'service() == null || controller() == null' in toggle
    assert '!isEnded() && controller().getPlayWhenReady()' in toggle
    assert 'player().isPlaying()' not in toggle  # Buffering must still be pausable.
    assert 'onPaused()' in toggle and 'onPlay()' in toggle and 'hideControl()' in toggle
    paused = body(java, 'private void onPaused()')
    assert 'controller().pause()' in paused
    if name == 'VideoActivity':
        assert 'showControl(mBinding.control.action.playPause)' in paused
    else:
        assert 'showControl()' in paused
        assert 'action.playPause.requestFocus()' in body(java, 'private void showControl()')
    callback = body(java, 'protected void onPlayingChanged(boolean isPlaying)')
    assert 'setPlayPauseText()' in callback
    assert '!controller().getPlayWhenReady()' in callback
    assert 'if (isGone(mBinding.control.getRoot())) showControl(' in callback  # Do not steal menu focus.
    ready = body(java, 'public void onPlayWhenReadyChanged(')
    assert 'if (isOwner()) setPlayPauseText()' in ready
    dispatch = body(java, 'public boolean dispatchKeyEvent(')
    assert 'isGone(mBinding.control.getRoot()) && mKeyDown.hasEvent(event)' in dispatch
    assert 'return super.dispatchKeyEvent(event)' in dispatch
    assert 'isEnterKey' not in dispatch  # Menu center still clicks its actual focused button.

print('PASS static TV play/pause binding / existing controls / focus chain / ready-state toggle / no global center theft')

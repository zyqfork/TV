const icDir = `data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23F5A623'><path d='M10 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z'/></svg>`;
const icFile = `data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%23717970'><path d='M14 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V8l-6-6zm4 18H6V4h7v5h5v11z'/></svg>`;
let currentRoot = '';
let currentFile = '';
let currentParent = '';
let longPressTimer = null;
let longPressTriggered = false;
let pendingDelFolder = null;
let warnToastTimer = null;
let danmakuMode = 1;
let danmakuSize = 25;
let dialogClosing = false;

const translations = {
    'zh-CN': {
        '影視': '影视', '請輸入關鍵字...': '请输入关键词...', '確定': '确定', '請輸入播放網址...': '请输入播放地址...',
        '滾動': '滚动', '預設': '默认', '請輸入彈幕內容...': '请输入弹幕内容...', '發送': '发送', '名稱': '名称',
        '設定': '设置', '上傳檔案': '上传文件', '新增資料夾': '新建文件夹', '搜尋': '搜索', '彈幕': '弹幕', '本地': '本地',
        '模式': '模式', '頂部': '顶部', '底部': '底部', '反向': '反向', '大小': '大小', '小': '小', '大': '大',
        '本地路徑': '本地路径', '關閉': '关闭', '使用': '使用', '確認上傳？': '确认上传？', '取消': '取消',
        '請輸入資料夾名稱...': '请输入文件夹名称...', '刪除資料夾': '删除文件夹', '刪除': '删除', '刪除檔案': '删除文件',
        '回應格式錯誤': '响应格式错误', '可能沒有存儲權限': '可能没有存储权限', '載入失敗': '加载失败',
        '新增失敗': '新建失败', '是否刪除 ': '是否删除 ', '刪除失敗': '删除失败'
    },
    en: {
        '影視': 'TV', '請輸入關鍵字...': 'Enter keywords...', '確定': 'Confirm', '請輸入播放網址...': 'Enter playback URL...',
        '滾動': 'Scroll', '預設': 'Default', '請輸入彈幕內容...': 'Enter comment...', '發送': 'Send', '名稱': 'Name',
        '配置': 'Configuration', '設定': 'Settings', '上傳檔案': 'Upload files', '新增資料夾': 'New folder', '搜尋': 'Search',
        '推送': 'Push', '彈幕': 'Comments', '本地': 'Local', '模式': 'Mode', '頂部': 'Top', '底部': 'Bottom',
        '反向': 'Reverse', '大小': 'Size', '小': 'Small', '大': 'Large', '本地路徑': 'Local path', '關閉': 'Close',
        '使用': 'Use', '確認上傳？': 'Confirm upload?', '取消': 'Cancel', '請輸入資料夾名稱...': 'Enter folder name...',
        '刪除資料夾': 'Delete folder', '刪除': 'Delete', '刪除檔案': 'Delete file', '回應格式錯誤': 'Invalid response format',
        '可能沒有存儲權限': 'Storage permission may be missing', '載入失敗': 'Load failed', '新增失敗': 'Create failed',
        '是否刪除 ': 'Delete ', '刪除失敗': 'Delete failed'
    }
};
let locale = 'zh-TW';

function t(text) {
    return translations[locale]?.[text] || text;
}

function applyLocale(value) {
    locale = translations[value] ? value : 'en';
    document.documentElement.lang = locale;
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const nodes = [];
    while (walker.nextNode()) nodes.push(walker.currentNode);
    nodes.forEach(node => {
        const value = node.nodeValue;
        const trimmed = value.trim();
        if (trimmed && t(trimmed) !== trimmed) node.nodeValue = value.replace(trimmed, t(trimmed));
    });
    document.querySelectorAll('[placeholder]').forEach(node => node.placeholder = t(node.placeholder));
}

function search() {
    doAction('search', { word: $('#keyword').val() });
}

function push() {
    doAction('push', { url: $('#push_url').val() });
}

function setting() {
    doAction('setting', { text: $('#setting_text').val(), name: $('#setting_name').val() });
}

function sendDanmaku() {
    const text = $('#danmaku_text').val().trim();
    if (!text) return;
    doAction('danmaku', { text: `[0.0,${danmakuMode},${danmakuSize},16777215]${text}` });
    $('#danmaku_text').val('');
}

function showDanmakuModeDialog() {
    $('#danmakuModeDialog .md-dialog-list-item').removeClass('active');
    $(`#danmakuModeDialog .md-dialog-list-item[data-val="${danmakuMode}"]`).addClass('active');
    openDialog('danmakuModeDialog');
}

function setDanmakuMode(val, label) {
    danmakuMode = val;
    $('#danmaku_mode_label').text(t(label));
    closeDialog('danmakuModeDialog');
}

function showDanmakuSizeDialog() {
    $('#danmakuSizeDialog .md-dialog-list-item').removeClass('active');
    $(`#danmakuSizeDialog .md-dialog-list-item[data-val="${danmakuSize}"]`).addClass('active');
    openDialog('danmakuSizeDialog');
}

function setDanmakuSize(val, label) {
    danmakuSize = val;
    $('#danmaku_size_label').text(t(label));
    closeDialog('danmakuSizeDialog');
}

function doAction(action, kv) {
    $.post('/action', { ...kv, do: action });
}

function openDialog(id) {
    $('#' + id).show();
    history.pushState({ dialog: id }, '');
}

function closeDialog(id) {
    dialogClosing = true;
    $('#' + id).hide();
    history.back();
}

function startLongPress(callback) {
    longPressTriggered = false;
    longPressTimer = setTimeout(() => {
        longPressTriggered = true;
        callback();
    }, 500);
}

function cancelLongPress() {
    if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
}

function escPath(s) {
    return s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/\\/g, '\\\\').replace(/'/g, "\\'");
}

function escHtml(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}

function buildParentItem() {
    return `<a class="file-item" href="javascript:void(0)" onclick="history.back()">
    <img class="file-icon" src="${icDir}" alt="">
    <div class="file-info"><div class="file-name">..</div></div>
    </a>`;
}

function buildDirItem(name, time, path) {
    const ep = escPath(path);
    return `<a class="file-item" href="javascript:void(0)" ontouchstart="startLongPress(()=>showDelFolderDialog('${ep}',currentRoot))" ontouchmove="cancelLongPress()" ontouchend="cancelLongPress()" oncontextmenu="return false" onclick="if(!longPressTriggered)listFile('${ep}',true)">
    <img class="file-icon" src="${icDir}" alt="">
    <div class="file-info"><div class="file-name">${escHtml(name)}</div><div class="file-time">${escHtml(time)}</div></div>
    </a>`;
}

function buildFileItem(name, time, path) {
    const ep = escPath(path);
    return `<a class="file-item" href="javascript:void(0)" ontouchstart="startLongPress(()=>showDelFileDialog('${ep}'))" ontouchmove="cancelLongPress()" ontouchend="cancelLongPress()" oncontextmenu="return false" onclick="if(!longPressTriggered)selectFile('${ep}')">
    <img class="file-icon" src="${icFile}" alt="">
    <div class="file-info"><div class="file-name">${escHtml(name)}</div><div class="file-time">${escHtml(time)}</div></div>
    </a>`;
}

function addFile(node) {
    $('#file_list').append(node);
}

function selectFile(path) {
    currentFile = path;
    $("#fileUrl").text("file:/" + path);
    openDialog('fileInfoDialog');
}

function pushFile(yes) {
    closeDialog('fileInfoDialog');
    if (yes === 1) doAction('file', { path: "file:/" + currentFile });
}

function listFile(path, addHistory = false) {
    const loadingTimer = setTimeout(() => $('#loadingToast').show(), 200);
    $.get('/file' + path, function (res) {
        clearTimeout(loadingTimer);
        let info;
        try {
            info = JSON.parse(res);
        } catch (e) {
            $('#loadingToast').hide();
            warnToast(t('回應格式錯誤'));
            return;
        }
        const parent = info.parent;
        currentRoot = path;
        currentParent = parent;
        const array = info.files;
        if (path === '' && array.length === 0) warnToast(t('可能沒有存儲權限'));
        $('#file_list').html('');
        if (parent !== '.') addFile(buildParentItem());
        array.forEach(node => {
            if (node.dir === 1) addFile(buildDirItem(node.name, node.time, node.path));
            else addFile(buildFileItem(node.name, node.time, node.path));
        });
        if (addHistory) history.pushState(path, '');
        $('#loadingToast').hide();
    }).fail(function () {
        clearTimeout(loadingTimer);
        $('#loadingToast').hide();
        warnToast(t('載入失敗'));
    });
}

function uploadFile() {
    $('#file_uploader').click();
}

function onFileSelected() {
    const files = $('#file_uploader')[0].files;
    if (files.length === 0) return;
    const tip = Array.from(files).map(f => f.name).join(', ');
    $('#uploadTipContent').text(tip);
    openDialog('uploadTip');
}

function confirmUpload(yes) {
    closeDialog('uploadTip');
    if (yes !== 1) return;
    const files = $('#file_uploader')[0].files;
    if (files.length === 0) return;
    const formData = new FormData();
    formData.append('path', currentRoot);
    Array.from(files).forEach((f, i) => formData.append('files-' + i, f));
    $('#loadingToast').show();
    $.ajax({
        url: '/upload',
        type: 'post',
        data: formData,
        processData: false,
        contentType: false,
        complete: function () {
            $('#loadingToast').hide();
            $('#file_uploader').val('');
            listFile(currentRoot);
        }
    });
}

function showNewFolderDialog() {
    openDialog('newFolder');
}

function confirmNewFolder(yes) {
    closeDialog('newFolder');
    const name = $('#newFolderContent').val().trim();
    $('#newFolderContent').val('');
    if (yes !== 1 || name.length === 0) return;
    $('#loadingToast').show();
    $.post('/newFolder', { path: currentRoot, name }, function () {
        $('#loadingToast').hide();
        listFile(currentRoot);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast(t('新增失敗'));
    });
}

function showDelFolderDialog(path, refreshPath) {
    pendingDelFolder = { path, refreshPath };
    $('#delFolderContent').text(t('是否刪除 ') + path);
    openDialog('delFolder');
}

function confirmDelFolder(yes) {
    closeDialog('delFolder');
    if (yes !== 1 || !pendingDelFolder) { pendingDelFolder = null; return; }
    const { path, refreshPath } = pendingDelFolder;
    pendingDelFolder = null;
    $('#loadingToast').show();
    $.post('/delFolder', { path }, function () {
        $('#loadingToast').hide();
        listFile(refreshPath);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast(t('刪除失敗'));
    });
}

function showDelFileDialog(path) {
    currentFile = path;
    $('#delFileContent').text(t('是否刪除 ') + path);
    openDialog('delFile');
}

function confirmDelFile(yes) {
    closeDialog('delFile');
    if (yes !== 1) return;
    $('#loadingToast').show();
    $.post('/delFile', { path: currentFile }, function () {
        $('#loadingToast').hide();
        listFile(currentRoot);
    }).fail(function () {
        $('#loadingToast').hide();
        warnToast(t('刪除失敗'));
    });
}

function warnToast(msg) {
    $('#warnToastContent').text(msg);
    $('#warnToast').show();
    if (warnToastTimer) clearTimeout(warnToastTimer);
    warnToastTimer = setTimeout(() => { $('#warnToast').hide(); warnToastTimer = null; }, 1000);
}

function showPanel(id) {
    for (let i = 1; i <= 5; i++) {
        document.getElementById('panel' + i).classList.toggle('active', i === id);
        document.getElementById('tab' + i).classList.toggle('active', i === id);
    }
    if (id === 5 && document.getElementById('file_list').innerHTML === '') listFile('');
}

const tab = parseInt(new URLSearchParams(window.location.search).get('tab')) || 1;
history.replaceState(null, '');
showPanel(tab);

window.addEventListener('popstate', function () {
    if (dialogClosing) { dialogClosing = false; return; }
    const visible = $('.md-dialog-overlay:visible');
    if (visible.length) { visible.first().hide(); return; }
    listFile(currentParent);
});

$(function () {
    $.get('/locale').done(applyLocale).fail(() => applyLocale('en'));
    $('#keyword').on('keydown', function (e) { if (e.key === 'Enter') { this.blur(); search(); } });
    $('#push_url').on('keydown', function (e) { if (e.key === 'Enter') { this.blur(); push(); } });
    $('#danmaku_text').on('keydown', function (e) { if (e.key === 'Enter') { this.blur(); sendDanmaku(); } });
    $('#setting_name, #setting_text').on('keydown', function (e) { if (e.key === 'Enter') { this.blur(); setting(); } });
    $('#newFolderContent').on('keydown', function (e) { if (e.key === 'Enter') { this.blur(); confirmNewFolder(1); } });
});

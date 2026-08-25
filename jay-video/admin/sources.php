<?php
/** Jay影视 - 管理后台 · 播放源管理 */
$ADMIN_PAGE = 'sources';
require_once __DIR__ . '/_header.php';

/* ---------- 动作 ---------- */
if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';

    if ($act === 'add' || $act === 'edit') {
        $name = trim($_POST['name'] ?? '');
        $url  = trim($_POST['api_url'] ?? '');
        $def  = !empty($_POST['is_default']);
        if ($name === '' || !preg_match('#^https?://#i', $url)) {
            flash_set('请填写正确的播放源名称与 API 地址', 'error');
        } else {
            if ($act === 'add') {
                db_x("INSERT INTO play_sources (name,api_url,is_default,status,sort,created_at) VALUES (?,?,?,?,0,NOW())",
                    [$name, $url, $def ? 1 : 0, 1]);
                if ($def) {
                    $newId = (int)db()->lastInsertId();
                    db_x("UPDATE play_sources SET is_default=0 WHERE id<>?", [$newId]);
                }
                flash_set('播放源已添加', 'success');
            } else {
                $sid = (int)($_POST['sid'] ?? 0);
                db_x("UPDATE play_sources SET name=?,api_url=?,is_default=? WHERE id=?", [$name, $url, $def ? 1 : 0, $sid]);
                if ($def) db_x("UPDATE play_sources SET is_default=0 WHERE id<>?", [$sid]);
                flash_set('播放源已更新', 'success');
            }
        }
    }

    if ($act === 'del') {
        $sid = (int)($_POST['sid'] ?? 0);
        $cnt = (int)db_val("SELECT COUNT(*) FROM play_sources");
        if ($cnt <= 1) {
            flash_set('至少保留一个播放源，无法删除', 'error');
        } else {
            $wasDefault = (int)db_val("SELECT is_default FROM play_sources WHERE id=?", [$sid]);
            db_x("DELETE FROM play_sources WHERE id=?", [$sid]);
            if ($wasDefault === 1) {
                db_x("UPDATE play_sources SET is_default=1 ORDER BY id ASC LIMIT 1");
            }
            flash_set('播放源已删除', 'success');
        }
    }

    if ($act === 'set_default') {
        $sid = (int)($_POST['sid'] ?? 0);
        if (db_val("SELECT id FROM play_sources WHERE id=?", [$sid])) {
            db_x("UPDATE play_sources SET is_default=0");
            db_x("UPDATE play_sources SET is_default=1 WHERE id=?", [$sid]);
            db_x("DELETE FROM cache WHERE cache_key LIKE 'src:%'");
            flash_set('默认播放源已切换', 'success');
        }
    }

    redirect(u('admin/sources.php'));
}

$sources = play_sources_all();
?>
<div class="admin-title">
  <div>
    <h1>播放源管理</h1>
    <p class="at-sub">播放页将从默认播放源接口获取真实 m3u8 直链并拼接解析播放器</p>
  </div>
</div>

<div class="dash-grid">
  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-plus"></i>添加播放源</h3></div>
    <div class="panel-body">
      <form method="post" action="<?= u('admin/sources.php') ?>">
        <?= csrf_field() ?>
        <input type="hidden" name="act" value="add">
        <div class="field">
          <label>播放源名称 <span class="req">*</span></label>
          <input class="input" type="text" name="name" placeholder="例如：雁北飞资源" required>
        </div>
        <div class="field">
          <label>资源接口地址 <span class="req">*</span></label>
          <input class="input" type="url" name="api_url" placeholder="https://api.xxx.com/inc/apijson.php" required>
          <p class="form-hint">需为 JSON 资源接口（支持 wd 搜索 / ids 详情参数）</p>
        </div>
        <label style="display:flex;align-items:center;gap:8px;font-size:13.5px;color:var(--text-2);margin-bottom:16px;cursor:pointer">
          <input type="checkbox" name="is_default" style="accent-color:var(--primary);width:15px;height:15px"> 设为默认播放源
        </label>
        <button class="btn btn-primary btn-block" type="submit"><i class="ic ic-plus"></i>添加播放源</button>
      </form>
    </div>
  </div>

  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-db"></i>播放源列表（<?= count($sources) ?>）</h3></div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>名称</th><th>接口地址</th><th>状态</th><th style="text-align:right">操作</th></tr></thead>
        <tbody>
          <?php foreach ($sources as $s): ?>
          <tr>
            <td><b><?= e($s['name']) ?></b><?= (int)$s['is_default'] === 1 ? ' <span class="tag-green">默认</span>' : '' ?>
              <div class="t-sub"><?= (int)$s['status'] === 1 ? '启用中' : '已停用' ?> · #<?= (int)$s['id'] ?></div>
            </td>
            <td style="max-width:260px"><span class="t-sub" style="word-break:break-all"><?= e($s['api_url']) ?></span></td>
            <td><?= (int)$s['is_default'] === 1 ? '<span class="tag-green">默认源</span>' : '<span class="tag-gray">备用源</span>' ?></td>
            <td style="text-align:right">
              <?php if ((int)$s['is_default'] !== 1): ?>
              <form method="post" action="<?= u('admin/sources.php') ?>" style="display:inline">
                <?= csrf_field() ?>
                <input type="hidden" name="act" value="set_default"><input type="hidden" name="sid" value="<?= (int)$s['id'] ?>">
                <button class="btn btn-green btn-xs" type="submit"><i class="ic ic-check"></i>设为默认</button>
              </form>
              <?php endif; ?>
              <button class="btn btn-ghost btn-xs" type="button" onclick='editSource(<?= json_encode([
                  'id' => (int)$s['id'], 'name' => $s['name'], 'url' => $s['api_url'], 'def' => (int)$s['is_default'],
              ], JSON_HEX_APOS | JSON_HEX_QUOT) ?>)'><i class="ic ic-edit"></i>编辑</button>
              <form method="post" action="<?= u('admin/sources.php') ?>" style="display:inline" data-confirm="确定删除播放源「<?= e($s['name']) ?>」吗？">
                <?= csrf_field() ?>
                <input type="hidden" name="act" value="del"><input type="hidden" name="sid" value="<?= (int)$s['id'] ?>">
                <button class="btn btn-danger btn-xs" type="submit"><i class="ic ic-trash"></i>删除</button>
              </form>
            </td>
          </tr>
          <?php endforeach; ?>
        </tbody>
      </table>
    </div>
  </div>
</div>

<!-- 编辑弹窗 -->
<div class="overlay" id="edit-overlay">
  <div class="modal">
    <div class="modal-head">
      <h3><i class="ic ic-edit"></i>编辑播放源</h3>
      <button class="modal-close" type="button" data-close><i class="ic ic-close"></i></button>
    </div>
    <form method="post" action="<?= u('admin/sources.php') ?>">
      <?= csrf_field() ?>
      <input type="hidden" name="act" value="edit">
      <input type="hidden" name="sid" id="es-id" value="">
      <div class="field">
        <label>播放源名称</label>
        <input class="input" type="text" name="name" id="es-name" required>
      </div>
      <div class="field">
        <label>资源接口地址</label>
        <input class="input" type="url" name="api_url" id="es-url" required>
      </div>
      <label style="display:flex;align-items:center;gap:8px;font-size:13.5px;color:var(--text-2);margin-bottom:16px;cursor:pointer">
        <input type="checkbox" name="is_default" id="es-def" style="accent-color:var(--primary);width:15px;height:15px"> 设为默认播放源
      </label>
      <button class="btn btn-primary btn-block" type="submit"><i class="ic ic-check"></i>保存修改</button>
    </form>
  </div>
</div>

<script>
function editSource(d) {
  document.getElementById('es-id').value = d.id;
  document.getElementById('es-name').value = d.name;
  document.getElementById('es-url').value = d.url;
  document.getElementById('es-def').checked = d.def === 1;
  document.getElementById('edit-overlay').classList.add('show');
}
</script>
<?php require_once __DIR__ . '/_footer.php'; ?>

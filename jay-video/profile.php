<?php
/** Jay影视 - 个人中心（收藏 / 观看历史 / 头像） */
require_once __DIR__ . '/includes/bootstrap.php';

$U = require_login();
$uid = (int)$U['id'];
$tab = $_GET['tab'] ?? 'account';
if (!in_array($tab, ['account', 'fav', 'history'], true)) $tab = 'account';

/* ---------- 动作处理 ---------- */
if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';

    /* 上传头像 */
    if ($act === 'avatar') {
        $file = $_FILES['avatar'] ?? null;
        $ok = false;
        if (!$file || $file['error'] !== UPLOAD_ERR_OK) {
            flash_set('请选择要上传的头像图片', 'error');
        } elseif ($file['size'] > 2 * 1024 * 1024) {
            flash_set('头像不能超过 2MB', 'error');
        } else {
            $info = @getimagesize($file['tmp_name']);
            $allow = [IMAGETYPE_JPEG => 'jpg', IMAGETYPE_PNG => 'png', IMAGETYPE_GIF => 'gif', IMAGETYPE_WEBP => 'webp'];
            if (!$info || !isset($allow[$info[2]])) {
                flash_set('仅支持 JPG / PNG / GIF / WEBP 图片', 'error');
            } else {
                $dir = ROOT . '/uploads/avatars';
                if (!is_dir($dir)) @mkdir($dir, 0755, true);
                $rel = 'uploads/avatars/u' . $uid . '_' . time() . '.' . $allow[$info[2]];
                if (move_uploaded_file($file['tmp_name'], ROOT . '/' . $rel)) {
                    if (!empty($U['avatar']) && is_file(ROOT . '/' . $U['avatar']) && strpos($U['avatar'], 'avatars/') === 0) {
                        @unlink(ROOT . '/' . $U['avatar']);
                    }
                    db_x("UPDATE users SET avatar=? WHERE id=?", [$rel, $uid]);
                    flash_set('头像更新成功', 'success');
                    $ok = true;
                } else {
                    flash_set('头像保存失败，请检查目录权限', 'error');
                }
            }
        }
        redirect(u('profile.php?tab=account'));
    }

    /* 取消收藏 */
    if ($act === 'remove_fav') {
        db_x("DELETE FROM favorites WHERE id=? AND user_id=?", [(int)($_POST['fid'] ?? 0), $uid]);
        flash_set('已取消收藏', 'info');
    }

    /* 删除单条历史 */
    if ($act === 'del_history') {
        db_x("DELETE FROM watch_history WHERE id=? AND user_id=?", [(int)($_POST['hid'] ?? 0), $uid]);
        flash_set('已删除该条观看记录', 'info');
    }

    /* 清空历史 */
    if ($act === 'clear_history') {
        db_x("DELETE FROM watch_history WHERE user_id=?", [$uid]);
        flash_set('观看记录已清空', 'info');
    }

    redirect(u('profile.php?tab=' . $tab));
}

/* ---------- 数据 ---------- */
$favCount = (int)db_val("SELECT COUNT(*) FROM favorites WHERE user_id=?", [$uid]);
$histCount = (int)db_val("SELECT COUNT(*) FROM watch_history WHERE user_id=?", [$uid]);
$favs = db_q("SELECT * FROM favorites WHERE user_id=? ORDER BY id DESC LIMIT 60", [$uid]);
$hists = db_q("SELECT * FROM watch_history WHERE user_id=? ORDER BY updated_at DESC LIMIT 60", [$uid]);

$PAGE_TITLE = '个人中心 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
$U = current_user();
?>
<div class="container">
  <div class="profile-head">
    <?= avatar_html($U, 'avatar-lg') ?>
    <div class="profile-info">
      <h2><?= e($U['username']) ?><?= $U['role'] === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?></h2>
      <p class="p-mail"><i class="ic ic-mail" style="width:13px;height:13px"></i> <?= e($U['email']) ?> · 注册于 <?= e(mb_substr($U['created_at'], 0, 10)) ?></p>
      <div class="profile-stats">
        <div class="pstat"><b><?= $favCount ?></b><span>收藏</span></div>
        <div class="pstat"><b><?= $histCount ?></b><span>观看记录</span></div>
        <div class="pstat"><b><?= $U['status'] == 1 ? '正常' : '封禁中' ?></b><span>账号状态</span></div>
      </div>
    </div>
    <?php if ($U['role'] === 'admin'): ?>
    <a class="btn btn-ghost" href="<?= u('admin/index.php') ?>"><i class="ic ic-db"></i>进入管理后台</a>
    <?php endif; ?>
  </div>

  <div class="tabs">
    <a class="tab <?= $tab === 'account' ? 'active' : '' ?>" href="<?= u('profile.php?tab=account') ?>"><i class="ic ic-user"></i>账号设置</a>
    <a class="tab <?= $tab === 'fav' ? 'active' : '' ?>" href="<?= u('profile.php?tab=fav') ?>"><i class="ic ic-heart"></i>我的收藏</a>
    <a class="tab <?= $tab === 'history' ? 'active' : '' ?>" href="<?= u('profile.php?tab=history') ?>"><i class="ic ic-clock"></i>观看历史</a>
  </div>

  <?php if ($tab === 'account'): ?>
  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-user"></i>头像设置</h3></div>
    <div class="panel-body">
      <form method="post" action="<?= u('profile.php?tab=account') ?>" enctype="multipart/form-data" class="avatar-upload" id="avatar-form">
        <?= csrf_field() ?>
        <input type="hidden" name="act" value="avatar">
        <div class="avatar-preview">
          <?= avatar_html($U, 'avatar-xl') ?>
          <label class="avatar-mask" for="avatar-file"><i class="ic ic-edit" style="width:20px;height:20px"></i></label>
        </div>
        <div style="flex:1;min-width:220px">
          <p style="color:var(--text-2);font-size:13.5px;margin-bottom:12px">支持 JPG / PNG / GIF / WEBP，大小不超过 2MB</p>
          <input type="file" id="avatar-file" class="file-hidden" name="avatar" accept="image/*" onchange="document.getElementById('avatar-form').submit()">
          <button class="btn btn-primary" type="button" onclick="document.getElementById('avatar-file').click()"><i class="ic ic-edit"></i>选择图片上传</button>
        </div>
      </form>
    </div>
  </div>

  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-info"></i>账号信息</h3></div>
    <div class="panel-body">
      <div class="mini-form-row">
        <div class="field"><label>用户名</label><input class="input" type="text" value="<?= e($U['username']) ?>" disabled></div>
        <div class="field"><label>邮箱</label><input class="input" type="text" value="<?= e($U['email']) ?>" disabled></div>
        <div class="field"><label>角色</label><input class="input" type="text" value="<?= $U['role'] === 'admin' ? '管理员（开发者）' : '普通用户' ?>" disabled></div>
        <div class="field"><label>注册时间</label><input class="input" type="text" value="<?= e($U['created_at']) ?>" disabled></div>
      </div>
    </div>
  </div>

  <?php elseif ($tab === 'fav'): ?>
  <div class="panel">
    <div class="panel-head">
      <h3><i class="ic ic-heart"></i>我的收藏（<?= $favCount ?>）</h3>
    </div>
    <div class="panel-body">
      <?php if ($favs): ?>
      <div class="media-grid">
        <?php foreach ($favs as $i => $f): ?>
        <div class="media-card">
          <a href="<?= u('detail.php?type=' . e($f['media_type']) . '&id=' . (int)$f['tmdb_id']) ?>">
            <div class="poster">
              <img class="poster-img" data-fade src="<?= e($f['poster']) ?>" alt="<?= e($f['title']) ?>" loading="lazy">
              <span class="poster-remark"><?= $f['media_type'] === 'tv' ? '剧集' : '电影' ?></span>
              <span class="poster-play"><i class="ic ic-play"></i></span>
            </div>
            <h4><?= e($f['title']) ?></h4>
            <div class="sub">收藏于 <?= e(mb_substr($f['created_at'], 0, 10)) ?></div>
          </a>
          <form method="post" action="<?= u('profile.php?tab=fav') ?>" style="margin-top:6px" data-confirm="确定取消收藏《<?= e($f['title']) ?>》吗？">
            <?= csrf_field() ?>
            <input type="hidden" name="act" value="remove_fav">
            <input type="hidden" name="fid" value="<?= (int)$f['id'] ?>">
            <button class="btn btn-ghost btn-xs btn-block" type="submit"><i class="ic ic-trash"></i>取消收藏</button>
          </form>
        </div>
        <?php endforeach; ?>
      </div>
      <?php else: ?>
      <div class="empty">
        <div class="empty-icon"><i class="ic ic-heart"></i></div>
        <p>还没有收藏，去发现好片吧</p>
        <a class="btn btn-primary" href="<?= u('index.php') ?>"><i class="ic ic-fire"></i>去逛逛</a>
      </div>
      <?php endif; ?>
    </div>
  </div>

  <?php else: ?>
  <div class="panel">
    <div class="panel-head">
      <h3><i class="ic ic-clock"></i>观看历史（<?= $histCount ?>）</h3>
      <?php if ($hists): ?>
      <form method="post" action="<?= u('profile.php?tab=history') ?>" data-confirm="确定清空全部观看记录吗？该操作不可恢复！">
        <?= csrf_field() ?>
        <input type="hidden" name="act" value="clear_history">
        <button class="btn btn-danger btn-sm" type="submit"><i class="ic ic-trash"></i>清空全部</button>
      </form>
      <?php endif; ?>
    </div>
    <div class="panel-body">
      <?php if ($hists): ?>
      <div class="hist-list">
        <?php foreach ($hists as $h): ?>
        <div class="hist-item">
          <img class="hist-poster" src="<?= e($h['poster']) ?>" alt="<?= e($h['title']) ?>" data-fade loading="lazy">
          <div class="hist-main">
            <h4><?= e($h['title']) ?></h4>
            <div class="h-sub">
              <span><?= $h['media_type'] === 'tv' ? '第 ' . (int)$h['season'] . ' 季 · 第 ' . (int)$h['episode'] . ' 集' : '正片' ?></span>
              <?php if ($h['episode_name']): ?><span><?= e($h['episode_name']) ?></span><?php endif; ?>
              <span class="prog"><i class="ic ic-eye"></i>已观看 <?= e(format_seconds((int)$h['position_seconds'])) ?></span>
              <span><?= e(time_ago($h['updated_at'])) ?></span>
            </div>
          </div>
          <div class="hist-acts">
            <a class="btn btn-primary btn-sm" href="<?= u('play.php?type=' . e($h['media_type']) . '&id=' . (int)$h['tmdb_id'] . '&season=' . (int)$h['season'] . '&episode=' . (int)$h['episode']) ?>">
              <i class="ic ic-play"></i>继续观看
            </a>
            <form method="post" action="<?= u('profile.php?tab=history') ?>" data-confirm="确定删除这条记录吗？">
              <?= csrf_field() ?>
              <input type="hidden" name="act" value="del_history">
              <input type="hidden" name="hid" value="<?= (int)$h['id'] ?>">
              <button class="btn btn-ghost btn-xs" type="submit"><i class="ic ic-trash"></i>删除</button>
            </form>
          </div>
        </div>
        <?php endforeach; ?>
      </div>
      <?php else: ?>
      <div class="empty">
        <div class="empty-icon"><i class="ic ic-clock"></i></div>
        <p>暂无观看记录，看过的影片会自动记录在这里</p>
        <a class="btn btn-primary" href="<?= u('index.php') ?>"><i class="ic ic-fire"></i>开始观影</a>
      </div>
      <?php endif; ?>
    </div>
  </div>
  <?php endif; ?>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

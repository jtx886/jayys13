<?php
/** Jay影视 - 管理后台 · 仪表盘 */
$ADMIN_PAGE = 'index';
require_once __DIR__ . '/_header.php';

$statUsers  = (int)db_val("SELECT COUNT(*) FROM users");
$statToday  = (int)db_val("SELECT COUNT(*) FROM users WHERE created_at>=CURDATE()");
$statFb     = (int)db_val("SELECT COUNT(*) FROM feedbacks");
$statFav    = (int)db_val("SELECT COUNT(*) FROM favorites");
$statHist   = (int)db_val("SELECT COUNT(*) FROM watch_history");
$statReply  = (int)db_val("SELECT COUNT(*) FROM feedback_replies");

$latestUsers = db_q("SELECT * FROM users ORDER BY id DESC LIMIT 8");
$latestFb = db_q(
    "SELECT f.*, u.username, u.avatar, u.role,
     (SELECT COUNT(*) FROM feedback_replies r WHERE r.feedback_id=f.id) AS rc
     FROM feedbacks f LEFT JOIN users u ON u.id=f.user_id
     ORDER BY f.id DESC LIMIT 6");

/* 观看历史筛选 */
$hFilter = (int)($_GET['hist_user'] ?? 0);
$histories = db_q(
    "SELECT h.*, u.username FROM watch_history h LEFT JOIN users u ON u.id=h.user_id "
    . ($hFilter ? "WHERE h.user_id=" . $hFilter . " " : "")
    . "ORDER BY h.updated_at DESC LIMIT 20");
$usersAll = db_q("SELECT id,username FROM users ORDER BY id DESC LIMIT 300");

/* 收藏筛选 */
$fFilter = (int)($_GET['fav_user'] ?? 0);
$favs = db_q(
    "SELECT f.*, u.username FROM favorites f LEFT JOIN users u ON u.id=f.user_id "
    . ($fFilter ? "WHERE f.user_id=" . $fFilter . " " : "")
    . "ORDER BY f.id DESC LIMIT 20");
?>

<div class="admin-title">
  <div>
    <h1>仪表盘</h1>
    <p class="at-sub">欢迎回来，<?= e($ADMIN['username']) ?> · 今天是 <?= date('Y年m月d日') ?></p>
  </div>
  <a class="btn btn-ghost" href="<?= u('index.php') ?>">查看前台</a>
</div>

<div class="stat-grid">
  <div class="stat-card"><div><b><?= $statUsers ?></b><span>注册用户（今日 +<?= $statToday ?>）</span></div></div>
  <div class="stat-card"><div><b><?= $statFb ?></b><span>用户反馈（回复 <?= $statReply ?>）</span></div></div>
  <div class="stat-card"><div><b><?= $statFav ?></b><span>收藏总数</span></div></div>
  <div class="stat-card"><div><b><?= $statHist ?></b><span>观看历史记录</span></div></div>
</div>

<div class="dash-grid">
  <div class="panel">
    <div class="panel-head"><h3>最新注册用户</h3><a class="btn btn-ghost btn-xs" href="<?= u('admin/users.php') ?>">全部用户</a></div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>用户</th><th>邮箱</th><th>状态</th><th>注册时间</th></tr></thead>
        <tbody>
          <?php if ($latestUsers): foreach ($latestUsers as $v): ?>
          <tr>
            <td><div class="t-user"><?= avatar_html($v, 'avatar-sm') ?><div><?= e($v['username']) ?><?= $v['role'] === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?><div class="t-sub">#<?= (int)$v['id'] ?></div></div></div></td>
            <td style="color:var(--text-2)"><?= e($v['email']) ?></td>
            <td><?= user_banned($v) ? '<span class="status-dot banned">封禁中</span>' : '<span class="status-dot">正常</span>' ?></td>
            <td style="color:var(--text-3)"><?= e($v['created_at']) ?></td>
          </tr>
          <?php endforeach; else: ?>
          <tr><td colspan="4" style="text-align:center;color:var(--text-3);padding:30px">暂无用户</td></tr>
          <?php endif; ?>
        </tbody>
      </table>
    </div>
  </div>

  <div class="panel">
    <div class="panel-head"><h3>最新反馈</h3><a class="btn btn-ghost btn-xs" href="<?= u('admin/feedback.php') ?>">反馈管理</a></div>
    <div class="tbl-wrap">
      <table class="tbl">
        <thead><tr><th>反馈</th><th>用户</th><th>回复</th><th>时间</th></tr></thead>
        <tbody>
          <?php if ($latestFb): foreach ($latestFb as $f): ?>
          <tr>
            <td><a href="<?= u('admin/feedback_view.php?id=' . (int)$f['id']) ?>" style="color:var(--text)"><?= e(mb_substr($f['title'], 0, 22)) ?></a><div class="t-sub"><?= e(mb_substr($f['content'], 0, 30)) ?>…</div></td>
            <td style="color:var(--text-2)"><?= e($f['username'] ?: '已注销') ?></td>
            <td><span class="tag-blue"><?= (int)$f['rc'] ?> 条</span></td>
            <td style="color:var(--text-3)"><?= e(time_ago($f['created_at'])) ?></td>
          </tr>
          <?php endforeach; else: ?>
          <tr><td colspan="4" style="text-align:center;color:var(--text-3);padding:30px">暂无反馈</td></tr>
          <?php endif; ?>
        </tbody>
      </table>
    </div>
  </div>
</div>

<div class="panel">
  <div class="panel-head"><h3>观看历史</h3>
    <form method="get" action="<?= u('admin/index.php') ?>" class="filter-bar" style="margin:0">
      <div class="field" style="min-width:170px">
        <select class="select" name="hist_user" onchange="this.form.submit()">
          <option value="0">全部用户</option>
          <?php foreach ($usersAll as $v): ?>
          <option value="<?= (int)$v['id'] ?>" <?= $hFilter === (int)$v['id'] ? 'selected' : '' ?>><?= e($v['username']) ?></option>
          <?php endforeach; ?>
        </select>
      </div>
      <span class="t-sub" style="align-self:center;color:var(--text-3);font-size:12px">共 <?= count($histories) ?> 条（最多显示20条）</span>
    </form>
  </div>
  <div class="tbl-wrap">
    <table class="tbl">
      <thead><tr><th>用户</th><th>影片</th><th>进度</th><th>集数</th><th>最近观看</th></tr></thead>
      <tbody>
        <?php if ($histories): foreach ($histories as $h): ?>
        <tr>
          <td><?= e($h['username'] ?: '已注销') ?></td>
          <td><a href="<?= u('detail.php?type=' . e($h['media_type']) . '&id=' . (int)$h['tmdb_id']) ?>" style="color:var(--text)"><?= e($h['title']) ?></a></td>
          <td><span class="tag-green"><?= e(format_seconds((int)$h['position_seconds'])) ?></span></td>
          <td><?= $h['media_type'] === 'tv' ? 'S' . (int)$h['season'] . 'E' . (int)$h['episode'] : '正片' ?></td>
          <td style="color:var(--text-3)"><?= e($h['updated_at']) ?></td>
        </tr>
        <?php endforeach; else: ?>
        <tr><td colspan="5" style="text-align:center;color:var(--text-3);padding:30px">暂无观看记录</td></tr>
        <?php endif; ?>
      </tbody>
    </table>
  </div>
</div>

<div class="panel">
  <div class="panel-head"><h3>用户收藏</h3>
    <form method="get" action="<?= u('admin/index.php') ?>" class="filter-bar" style="margin:0">
      <div class="field" style="min-width:170px">
        <select class="select" name="fav_user" onchange="this.form.submit()">
          <option value="0">全部用户</option>
          <?php foreach ($usersAll as $v): ?>
          <option value="<?= (int)$v['id'] ?>" <?= $fFilter === (int)$v['id'] ? 'selected' : '' ?>><?= e($v['username']) ?></option>
          <?php endforeach; ?>
        </select>
      </div>
      <span class="t-sub" style="align-self:center;color:var(--text-3);font-size:12px">共 <?= count($favs) ?> 条（最多显示20条）</span>
    </form>
  </div>
  <div class="tbl-wrap">
    <table class="tbl">
      <thead><tr><th>用户</th><th>影片</th><th>类型</th><th>收藏时间</th></tr></thead>
      <tbody>
        <?php if ($favs): foreach ($favs as $f): ?>
        <tr>
          <td><?= e($f['username'] ?: '已注销') ?></td>
          <td><a href="<?= u('detail.php?type=' . e($f['media_type']) . '&id=' . (int)$f['tmdb_id']) ?>" style="color:var(--text)"><?= e($f['title']) ?></a></td>
          <td><?= $f['media_type'] === 'tv' ? '<span class="tag-blue">剧集</span>' : '<span class="tag-gray">电影</span>' ?></td>
          <td style="color:var(--text-3)"><?= e($f['created_at']) ?></td>
        </tr>
        <?php endforeach; else: ?>
        <tr><td colspan="4" style="text-align:center;color:var(--text-3);padding:30px">暂无收藏</td></tr>
        <?php endif; ?>
      </tbody>
    </table>
  </div>
</div>

<?php require_once __DIR__ . '/_footer.php'; ?>

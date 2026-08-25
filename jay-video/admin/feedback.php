<?php
/** Jay影视 - 管理后台 · 反馈管理（列表） */
$ADMIN_PAGE = 'feedback';
require_once __DIR__ . '/_header.php';

if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';
    if ($act === 'del') {
        $fid = (int)($_POST['fid'] ?? 0);
        db_x("DELETE FROM feedbacks WHERE id=?", [$fid]);
        db_x("DELETE FROM feedback_replies WHERE feedback_id=?", [$fid]);
        db_x("DELETE FROM feedback_likes WHERE feedback_id=?", [$fid]);
        flash_set('反馈及其回复已删除', 'success');
    }
    redirect(u('admin/feedback.php'));
}

$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 15;
$total = (int)db_val("SELECT COUNT(*) FROM feedbacks");
$totalPages = max(1, (int)ceil($total / $perPage));
$page = min($page, $totalPages);
$offset = ($page - 1) * $perPage;

$list = db_q(
    "SELECT f.*, u.username, u.avatar, u.role,
     (SELECT COUNT(*) FROM feedback_replies r WHERE r.feedback_id=f.id) AS rc,
     (SELECT COUNT(*) FROM feedback_replies r WHERE r.feedback_id=f.id AND r.user_id={$ADMIN['id']}) AS myrc
     FROM feedbacks f LEFT JOIN users u ON u.id=f.user_id
     ORDER BY f.id DESC LIMIT {$perPage} OFFSET {$offset}");
?>
<div class="admin-title">
  <div>
    <h1>反馈管理</h1>
    <p class="at-sub">共 <?= $total ?> 条反馈 · 支持以管理员身份回复</p>
  </div>
</div>

<div class="panel">
  <div class="tbl-wrap">
    <table class="tbl">
      <thead><tr><th>反馈内容</th><th>用户</th><th>点赞</th><th>回复数</th><th>时间</th><th style="text-align:right">操作</th></tr></thead>
      <tbody>
        <?php if ($list): foreach ($list as $f): ?>
        <tr>
          <td style="max-width:340px">
            <a href="<?= u('admin/feedback_view.php?id=' . (int)$f['id']) ?>" style="color:var(--text);font-weight:600"><?= e($f['title']) ?></a>
            <div class="t-sub"><?= e(mb_substr($f['content'], 0, 40)) ?><?= mb_strlen($f['content']) > 40 ? '…' : '' ?></div>
          </td>
          <td><?= e($f['username'] ?: '已注销') ?><?= ($f['role'] ?? '') === 'admin' ? ' <span class="badge-dev">开发者</span>' : '' ?></td>
          <td><span class="tag-blue"><?= (int)$f['likes'] ?></span></td>
          <td><span class="tag-green"><?= (int)$f['rc'] ?> 条</span><?= (int)$f['myrc'] > 0 ? '<div class="t-sub">已回复</div>' : '' ?></td>
          <td style="color:var(--text-3)"><?= e($f['created_at']) ?></td>
          <td style="text-align:right">
            <a class="btn btn-primary btn-xs" href="<?= u('admin/feedback_view.php?id=' . (int)$f['id']) ?>">查看 / 回复</a>
            <form method="post" action="<?= u('admin/feedback.php') ?>" style="display:inline" data-confirm="删除该反馈将同时删除全部回复与点赞，确定吗？">
              <?= csrf_field() ?>
              <input type="hidden" name="act" value="del"><input type="hidden" name="fid" value="<?= (int)$f['id'] ?>">
              <button class="btn btn-danger btn-xs" type="submit">删除</button>
            </form>
          </td>
        </tr>
        <?php endforeach; else: ?>
        <tr><td colspan="6" style="text-align:center;color:var(--text-3);padding:34px">暂无反馈</td></tr>
        <?php endif; ?>
      </tbody>
    </table>
  </div>
</div>
<?= pagination_html(u('admin/feedback.php') . '?', $page, $totalPages) ?>
<?php require_once __DIR__ . '/_footer.php'; ?>

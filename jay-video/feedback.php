<?php
/** Jay影视 - 反馈中心（列表 + 提交） */
require_once __DIR__ . '/includes/bootstrap.php';

$U = current_user();

/* 提交反馈 */
if (is_post()) {
    if (!$U) { flash_set('请先登录后再提交反馈', 'error'); redirect(u('login.php')); }
    if (!csrf_check()) csrf_fail();
    $title = trim($_POST['title'] ?? '');
    $content = trim($_POST['content'] ?? '');
    if (mb_strlen($title) < 2 || mb_strlen($title) > 100) {
        flash_set('反馈标题需为 2-100 字', 'error');
    } elseif (mb_strlen($content) < 5) {
        flash_set('反馈内容至少 5 个字', 'error');
    } else {
        db_x("INSERT INTO feedbacks (user_id,title,content,is_public,created_at) VALUES (?,?,?,1,NOW())",
            [(int)$U['id'], mb_substr($title, 0, 100), $content]);
        $fid = (int)db_val("SELECT LAST_INSERT_ID()");
        flash_set('反馈提交成功，感谢你的建议！', 'success');
        redirect(u('feedback_view.php?id=' . $fid));
    }
}

$page = max(1, (int)($_GET['page'] ?? 1));
$perPage = 10;
$total = (int)db_val("SELECT COUNT(*) FROM feedbacks WHERE is_public=1");
$totalPages = max(1, (int)ceil($total / $perPage));
$page = min($page, $totalPages);
$offset = ($page - 1) * $perPage;

$list = db_q(
    "SELECT f.*, u.username, u.avatar, u.role,
            (SELECT COUNT(*) FROM feedback_replies r WHERE r.feedback_id=f.id) AS replies_count,
            (SELECT COUNT(*) FROM feedback_likes l WHERE l.feedback_id=f.id AND l.user_id=" . (int)($U['id'] ?? 0) . ") AS liked
     FROM feedbacks f LEFT JOIN users u ON u.id=f.user_id
     WHERE f.is_public=1
     ORDER BY f.id DESC LIMIT {$perPage} OFFSET {$offset}"
);

$stats = [
    'total' => $total,
    'replies' => (int)db_val("SELECT COUNT(*) FROM feedback_replies r JOIN feedbacks f ON f.id=r.feedback_id WHERE f.is_public=1"),
    'mine' => $U ? (int)db_val("SELECT COUNT(*) FROM feedbacks WHERE user_id=?", [(int)$U['id']]) : 0,
];

$PAGE_TITLE = '反馈中心 - ' . site_name();
require_once __DIR__ . '/includes/header.php';
?>
<div class="container">
  <div class="page-head">
    <div>
      <h1>反馈中心</h1>
      <p class="ph-sub">提交建议与问题 · 公开交流 · 点赞回复</p>
    </div>
  </div>

  <div class="fb-layout">
    <div>
      <?php if ($list): foreach ($list as $f): ?>
      <div class="fb-card" style="margin-bottom:20px">
        <div class="fb-item-head">
          <?= avatar_html(['username' => $f['username'] ?? '已注销', 'avatar' => $f['avatar'] ?? '', 'role' => $f['role'] ?? 'user']) ?>
          <div style="flex:1;min-width:0">
            <div class="fb-item-meta">
              <span class="author"><?= name_html($f['username'] ? $f : null) ?></span>
              <span><?= e(time_ago($f['created_at'])) ?></span>
            </div>
          </div>
          <?php if ($f['replies_count'] > 0): ?><span class="reply-count-pill"><?= (int)$f['replies_count'] ?> 条回复</span><?php endif; ?>
        </div>
        <a href="<?= u('feedback_view.php?id=' . (int)$f['id']) ?>">
          <h3 class="fb-item-title" style="margin-top:12px"><?= e($f['title']) ?></h3>
          <p class="fb-content" style="margin-bottom:10px;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden"><?= e(mb_substr($f['content'], 0, 160)) ?></p>
        </a>
        <div class="fb-actions">
          <button class="fb-act like-btn <?= $f['liked'] ? 'liked' : '' ?>" data-id="<?= (int)$f['id'] ?>">
            赞 <span><?= (int)$f['likes'] ?></span>
          </button>
          <a class="fb-act" href="<?= u('feedback_view.php?id=' . (int)$f['id']) ?>">回复</a>
          <a class="fb-act" href="<?= u('feedback_view.php?id=' . (int)$f['id']) ?>" style="margin-left:auto">查看详情</a>
        </div>
      </div>
      <?php endforeach; else: ?>
      <div class="fb-card">
        <div class="empty" style="padding:40px 20px">
          <p>还没有公开反馈，来发布第一条吧！</p>
        </div>
      </div>
      <?php endif; ?>
      <?= pagination_html(u('feedback.php') . '?', $page, $totalPages) ?>
    </div>

    <aside>
      <div class="sidebar-card">
        <h3>发布反馈</h3>
        <?php if ($U): ?>
        <form method="post" action="<?= u('feedback.php') ?>">
          <?= csrf_field() ?>
          <div class="field">
            <label>反馈标题</label>
            <input class="input" type="text" name="title" maxlength="100" placeholder="一句话概括你的建议或问题" required>
          </div>
          <div class="field">
            <label>详细内容</label>
            <textarea class="textarea" name="content" placeholder="请详细描述…（至少 5 个字）" required></textarea>
          </div>
          <button class="btn btn-primary btn-block" type="submit">提交反馈</button>
        </form>
        <?php else: ?>
        <p style="color:var(--text-2);font-size:13px;margin-bottom:16px">登录后即可提交反馈、点赞与回复他人反馈。</p>
        <a class="btn btn-primary btn-block" href="<?= u('login.php') ?>">去登录</a>
        <?php endif; ?>
      </div>

      <div class="sidebar-card">
        <h3>社区动态</h3>
        <div style="display:flex;flex-direction:column;gap:14px">
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span style="color:var(--text-2);font-size:13px">公开反馈</span>
            <b><?= (int)$stats['total'] ?></b>
          </div>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span style="color:var(--text-2);font-size:13px">累计回复</span>
            <b><?= (int)$stats['replies'] ?></b>
          </div>
          <?php if ($U): ?>
          <div style="display:flex;justify-content:space-between;align-items:center">
            <span style="color:var(--text-2);font-size:13px">我的反馈</span>
            <b><?= (int)$stats['mine'] ?></b>
          </div>
          <?php endif; ?>
        </div>
      </div>

      <div class="sidebar-card">
        <h3>反馈规范</h3>
        <p style="font-size:12.5px;color:var(--text-3);line-height:2">
          1. 反馈默认公开，所有人可见可回复<br>
          2. 管理员回复将优先展示<br>
          3. 超过 3 条回复自动折叠<br>
          4. 请文明发言，违规将被封禁
        </p>
      </div>
    </aside>
  </div>
</div>
<?php require_once __DIR__ . '/includes/footer.php'; ?>

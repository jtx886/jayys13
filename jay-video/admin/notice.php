<?php
/** Jay影视 - 管理后台 · 网站公告（仅首页弹窗，新公告自动使Cookie失效） */
$ADMIN_PAGE = 'notice';
require_once __DIR__ . '/_header.php';

if (is_post()) {
    if (!csrf_check()) csrf_fail();
    $act = $_POST['act'] ?? '';

    if ($act === 'publish') {
        $title = trim($_POST['title'] ?? '');
        $content = trim($_POST['content'] ?? '');
        if ($title === '' || $content === '') {
            flash_set('请填写公告标题与内容', 'error');
        } else {
            // 新公告：仅保留最新一条为激活状态，公告ID变化使旧Cookie自动失效
            db_x("UPDATE notices SET is_active=0 WHERE is_active=1");
            db_x("INSERT INTO notices (title,content,is_active,created_at) VALUES (?,?,1,NOW())",
                [mb_substr($title, 0, 100), $content]);
            flash_set('公告已发布，首页将重新弹窗展示', 'success');
        }
    }

    if ($act === 'revoke') {
        db_x("UPDATE notices SET is_active=0 WHERE is_active=1");
        flash_set('公告已下线，首页不再弹窗', 'info');
    }

    redirect(u('admin/notice.php'));
}

$active = db_one("SELECT * FROM notices WHERE is_active=1 ORDER BY id DESC LIMIT 1");
$history = db_q("SELECT * FROM notices ORDER BY id DESC LIMIT 15");
?>
<div class="admin-title">
  <div>
    <h1>网站公告</h1>
    <p class="at-sub">公告仅在首页弹窗展示 · 用户勾选「不再提示」后记录 Cookie · 发布新公告后 Cookie 自动失效重新弹窗</p>
  </div>
</div>

<div class="dash-grid">
  <div class="panel">
    <div class="panel-head"><h3>发布新公告</h3></div>
    <div class="panel-body">
      <form method="post" action="<?= u('admin/notice.php') ?>">
        <?= csrf_field() ?>
        <input type="hidden" name="act" value="publish">
        <div class="field">
          <label>公告标题 <span class="req">*</span></label>
          <input class="input" type="text" name="title" maxlength="100" placeholder="例如：本周新增热门剧集" required>
        </div>
        <div class="field">
          <label>公告内容 <span class="req">*</span></label>
          <textarea class="textarea" name="content" style="min-height:150px" placeholder="输入公告正文…" required></textarea>
        </div>
        <button class="btn btn-primary btn-block" type="submit">发布公告</button>
      </form>
    </div>
  </div>

  <div>
    <div class="panel">
      <div class="panel-head">
        <h3>当前生效公告</h3>
        <?php if ($active): ?>
        <form method="post" action="<?= u('admin/notice.php') ?>" data-confirm="确定下线当前公告吗？">
          <?= csrf_field() ?>
          <input type="hidden" name="act" value="revoke">
          <button class="btn btn-danger btn-sm" type="submit">下线公告</button>
        </form>
        <?php endif; ?>
      </div>
      <div class="panel-body">
        <?php if ($active): ?>
        <h4 style="font-size:16px;margin-bottom:12px"><?= e($active['title']) ?></h4>
        <div class="notice-preview"><?= e($active['content']) ?></div>
        <p style="color:var(--text-3);font-size:12px;margin-top:10px">发布于 <?= e($active['created_at']) ?> · ID #<?= (int)$active['id'] ?></p>
        <?php else: ?>
        <div class="empty" style="padding:26px">
          <p>暂无生效公告</p>
        </div>
        <?php endif; ?>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head"><h3>历史公告</h3></div>
      <div class="tbl-wrap">
        <table class="tbl">
          <thead><tr><th>标题</th><th>状态</th><th>发布时间</th></tr></thead>
          <tbody>
            <?php if ($history): foreach ($history as $n): ?>
            <tr>
              <td><?= e($n['title']) ?></td>
              <td><?= (int)$n['is_active'] === 1 ? '<span class="tag-green">生效中</span>' : '<span class="tag-gray">已下线</span>' ?></td>
              <td style="color:var(--text-3)"><?= e($n['created_at']) ?></td>
            </tr>
            <?php endforeach; else: ?>
            <tr><td colspan="3" style="text-align:center;color:var(--text-3);padding:26px">暂无公告记录</td></tr>
            <?php endif; ?>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</div>
<?php require_once __DIR__ . '/_footer.php'; ?>

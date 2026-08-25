<?php if (!defined('JAY_BOOTSTRAP')) { exit('403'); } ?>
<footer class="footer">
  <div class="container footer-inner">
    <a class="logo" href="<?= u('index.php') ?>" style="font-size:16px">
      <span class="logo-mark" style="width:28px;height:28px;border-radius:8px"><i class="logo-play" style="border-top-width:6px;border-bottom-width:6px;border-left-width:9px"></i></span>
      <span class="logo-text"><?= e(site_name()) ?></span>
    </a>
    <div class="f-links">
      <a href="<?= u('index.php') ?>">首页</a>
      <a href="<?= u('category.php?type=movie') ?>">电影</a>
      <a href="<?= u('category.php?type=tv') ?>">剧集</a>
      <a href="<?= u('feedback.php') ?>">反馈中心</a>
    </div>
    <p>© <?= date('Y') ?> <?= e(site_name()) ?> · 影视数据来源 TMDB · 本站不存储任何视频文件</p>
  </div>
</footer>
<script src="<?= u('assets/js/app.js') ?>"></script>
</body>
</html>

<?php
/** Jay影视 - 管理后台 · 网站设置（全站主题色 / 站名 / TMDB Key） */
$ADMIN_PAGE = 'settings';
require_once __DIR__ . '/_header.php';

if (is_post()) {
    if (!csrf_check()) csrf_fail();

    $siteName = trim($_POST['site_name'] ?? '');
    $theme    = trim($_POST['theme_color'] ?? '');
    $tmdbKey  = trim($_POST['tmdb_api_key'] ?? '');

    if ($siteName === '') $siteName = 'Jay影视';
    if (!preg_match('/^#[0-9a-fA-F]{6}$/', $theme)) $theme = '#e50914';

    save_setting('site_name', mb_substr($siteName, 0, 30));
    save_setting('theme_color', $theme);
    save_setting('tmdb_api_key', $tmdbKey);
    db_x("DELETE FROM cache WHERE cache_key LIKE 'tmdb:%'"); // 清理媒体缓存

    flash_set('网站设置已保存', 'success');
    redirect(u('admin/settings.php'));
}

$curTheme = setting('theme_color', '#e50914');
$curName  = setting('site_name', 'Jay影视');
$curKey   = setting('tmdb_api_key', '');
$presets  = ['#e50914', '#7c3aed', '#0ea5e9', '#10b981', '#f59e0b', '#ec4899', '#ef4444', '#6366f1'];
?>
<div class="admin-title">
  <div>
    <h1>网站设置</h1>
    <p class="at-sub">主题颜色即时作用于全站按钮、高亮与组件</p>
  </div>
</div>

<div class="dash-grid">
  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-sliders"></i>基本设置</h3></div>
    <div class="panel-body">
      <form method="post" action="<?= u('admin/settings.php') ?>">
        <?= csrf_field() ?>
        <div class="field">
          <label>网站名称</label>
          <input class="input" type="text" name="site_name" maxlength="30" value="<?= e($curName) ?>" required>
        </div>

        <div class="field">
          <label>主题颜色</label>
          <input class="color-input-big" type="color" name="theme_color" id="theme-color" value="<?= e($curTheme) ?>" oninput="previewTheme(this)">
          <div class="color-presets">
            <?php foreach ($presets as $p): ?>
            <button class="color-dot <?= strtolower($p) === strtolower($curTheme) ? 'active' : '' ?>" type="button"
                    style="background:<?= e($p) ?>"
                    onclick="document.getElementById('theme-color').value='<?= e($p) ?>';previewTheme(document.getElementById('theme-color'));document.querySelectorAll('.color-dot').forEach(function(d){d.classList.remove('active')});this.classList.add('active')"></button>
            <?php endforeach; ?>
          </div>
          <p class="form-hint">点击色板快速选择，或使用取色器自定义；保存后对全站生效</p>
        </div>

        <div class="field">
          <label>TMDB API Key（影视元数据）</label>
          <input class="input" type="text" name="tmdb_api_key" value="<?= e($curKey) ?>" placeholder="在 themoviedb.org 免费申请 (v3 auth)">
          <p class="form-hint">数据接口代理：api.tmdb.org · 图片代理：images.tmdb.org/t/p · 留空则前台不展示影视内容</p>
        </div>

        <button class="btn btn-primary btn-block" type="submit"><i class="ic ic-check"></i>保存设置</button>
      </form>
    </div>
  </div>

  <div class="panel">
    <div class="panel-head"><h3><i class="ic ic-info"></i>系统信息</h3></div>
    <div class="panel-body">
      <table class="tbl" style="min-width:0">
        <tbody>
          <tr><td style="color:var(--text-3);width:130px">PHP 版本</td><td><?= e(PHP_VERSION) ?></td></tr>
          <tr><td style="color:var(--text-3)">数据库</td><td>MySQL · <?= e(DB_NAME) ?>@<?= e(DB_HOST) ?></td></tr>
          <tr><td style="color:var(--text-3)">SMTP 服务</td><td><?= e(SMTP_USER) ?> @ <?= e(SMTP_HOST) ?>:<?= e(SMTP_PORT) ?>（SSL）</td></tr>
          <tr><td style="color:var(--text-3)">解析播放器</td><td style="word-break:break-all"><?= e(PLAYER_SHELL) ?></td></tr>
          <tr><td style="color:var(--text-3)">默认播放源</td>
              <td><?php $ds = play_source_default(); echo $ds ? e($ds['name']) . '（' . e($ds['api_url']) . '）' : '未配置'; ?></td></tr>
          <tr><td style="color:var(--text-3)">媒体缓存</td><td><?= (int)db_val("SELECT COUNT(*) FROM cache") ?> 条（自动过期）</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</div>
<?php require_once __DIR__ . '/_footer.php'; ?>

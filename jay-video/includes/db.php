<?php
/**
 * Jay影视 - 数据库连接（PDO 单例，PHP 7.4+ / 8.x）
 */
function db(): PDO
{
    static $pdo = null;
    if ($pdo === null) {
        $dsn = 'mysql:host=' . DB_HOST . ';port=' . DB_PORT . ';dbname=' . DB_NAME . ';charset=utf8mb4';
        try {
            $pdo = new PDO($dsn, DB_USER, DB_PASS, [
                PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
                PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
                PDO::ATTR_EMULATE_PREPARES   => false,
            ]);
        } catch (PDOException $e) {
            die('<div style="padding:40px;font-family:sans-serif;background:#0a0e15;color:#e9edf5;min-height:100vh">
            <h2 style="color:#ff5d5d">数据库连接失败</h2><p style="color:#8b98af">' . htmlspecialchars($e->getMessage()) . '</p>
            <p style="color:#5d6a80">请检查 includes/config.php 中的数据库配置。</p></div>');
        }
    }
    return $pdo;
}

/** 查询多行 */
function db_q(string $sql, array $params = []): array
{
    $st = db()->prepare($sql);
    $st->execute($params);
    return $st->fetchAll();
}

/** 查询单行 */
function db_one(string $sql, array $params = [])
{
    $st = db()->prepare($sql);
    $st->execute($params);
    $row = $st->fetch();
    return $row === false ? null : $row;
}

/** 查询单值 */
function db_val(string $sql, array $params = [])
{
    $st = db()->prepare($sql);
    $st->execute($params);
    $v = $st->fetchColumn();
    return $v === false ? null : $v;
}

/** 执行写操作，返回影响行数 */
function db_x(string $sql, array $params = []): int
{
    $st = db()->prepare($sql);
    $st->execute($params);
    return $st->rowCount();
}

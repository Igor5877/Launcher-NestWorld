# Інтеграція краш-репортів з Azuriom Support (тікети)

LaunchServer після збереження краш-репорту надсилає його на сайт —
у плагіні Support автоматично створюється тікет. Повторні однакові краші
(той самий stack trace) **не створюють нових тікетів** — додається коментар
до вже відкритого (дедуплікація по хешу `[#xxxxxxxxxxxx]` у темі тікета).

## Схема

```
Гра (краш) → LauncherClient (WatchService) → LaunchServer (WebSocket, зберігає файл)
    → POST /api/support/crash (Bearer token) → тікет/коментар у Support
```

## 1. Файли на сайт (Azuriom)

Скопіювати в `plugins/support/` на сайті:

| Звідси | Куди |
|---|---|
| `src/Controllers/Api/CrashReportController.php` | `plugins/support/src/Controllers/Api/CrashReportController.php` |
| `routes/api.php` | `plugins/support/routes/api.php` |
| `src/Providers/RouteServiceProvider.php` | `plugins/support/src/Providers/RouteServiceProvider.php` (замінити) |

`RouteServiceProvider` відрізняється від оригіналу лише блоком, що підвантажує
`routes/api.php` з middleware `api` (без сесії та CSRF). При оновленні плагіна
Support цей файл треба буде замінити знову.

Потім на сайті:

```bash
php artisan route:clear && php artisan cache:clear
```

## 2. Налаштування на сайті (tinker)

```bash
php artisan tinker
```

```php
// Токен для LaunchServer (згенерувати і скопіювати собі)
\Azuriom\Models\Setting::updateSettings(['support.crash_token' => \Illuminate\Support\Str::random(48)]);
setting('support.crash_token'); // показати згенерований токен

// ID категорії Support для автоматичних крашів (створити категорію "Краші" в адмінці)
\Azuriom\Models\Setting::updateSettings(['support.crash_category' => 3]);

// (опційно) ID користувача-фолбека, якщо нік з лаунчера не знайдено серед users
\Azuriom\Models\Setting::updateSettings(['support.crash_fallback_user' => 1]);
```

## 3. Налаштування LaunchServer

У `LaunchServer.json` → `components` → блок `crashReport` додати:

```json
"ticketApiUrl": "https://ваш-сайт/api/support/crash",
"ticketApiToken": "токен_з_кроку_2"
```

Або через системні властивості: `-Dcrash.ticket.url=... -Dcrash.ticket.token=...`
Якщо будь-яке з двох полів не задане — інтеграція вимкнена, краші просто
зберігаються на диск як раніше.

## 4. Перевірка

```bash
curl -s -X POST https://ваш-сайт/api/support/crash \
  -H "Authorization: Bearer ВАШ_ТОКЕН" \
  -H "Content-Type: application/json" \
  -d '{"username":"Igor5877","subject":"Crash • Test 1.16.5 • NPE [#abcdef123456]","content":"Minecraft Crash Report\njava.lang.NullPointerException\n\tat net.minecraft.test","hash":"abcdef123456"}'
```

Очікувано: `{"status":"ticket_created","ticket_id":N}` (201).
Повторний виклик з тим самим `hash`: `{"status":"comment_added",...}` (200).

## Поведінка

- Автор тікета — користувач сайту з таким самим ніком, як у лаунчері
  (Azuriom-auth гарантує збіг). Якщо не знайдено — `support.crash_fallback_user`.
- У коментар потрапляє витяг краша (до ~12 KB, ліміт `ticketCommentMaxChars`
  у компоненті) + шлях до повного файлу на LaunchServer.
- Тема: `Crash • <профіль> <версія> • <Description з репорту> [#хеш]`.
- Хеш рахується зі stack trace без номерів рядків — той самий баг у різних
  гравців/запусків групується в один тікет, поки він відкритий. Закрили
  тікет → наступний такий краш створить новий.
- Discord-webhook плагіна Support (якщо налаштований) спрацьовує штатно.

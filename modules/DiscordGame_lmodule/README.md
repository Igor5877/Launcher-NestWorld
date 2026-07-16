# DiscordGame

Інтеграція лаунчера з Discord Rich Presence: поки гравець запустив клієнт (або просто відкрив
лаунчер), в його акаунті Discord показується, що він грає саме у вас.

Адаптовано з [GravitLauncher/LauncherModules](https://github.com/GravitLauncher/LauncherModules/tree/master/DiscordGame_lmodule)
під цей форк: `discord-game-sdk4j` v1.0.0 — чиста Java-реалізація IPC-протоколу Discord,
нативних бібліотек не потребує.

---

## Збірка

```
./gradlew :modules:DiscordGame_lmodule:jar
```

Готовий jar: `modules/DiscordGame_lmodule/build/libs/DiscordGame_lmodule-1.1.0.1.jar`

## Встановлення

1. Скопіювати зібраний **DiscordGame_lmodule-1.1.0.1.jar** в `/LaunchServer/launcher-modules/`
2. Скачати `discord-game-sdk4j-v1.0.0.jar` (JitPack): https://jitpack.io/com/github/JnCrMx/discord-game-sdk4j/v1.0.0/discord-game-sdk4j-v1.0.0.jar
3. Покласти цю бібліотеку в `/LaunchServer/launcher-libraries/`

`gson` навмисно виключений з бібліотеки при збірці — його вже надає базовий `LauncherCore`,
дублювати не потрібно (і не варто, щоб уникнути конфлікту версій).

---

## Налаштування модуля

Конфіг генерується в `modules.discordgame.*` (LauncherServer config), приклад:
```json
{
  "enable": true,
  "appid": 810913859371532298,
  "scopes": {
    "login": {
      "details": "NestWorld Launcher",
      "state": "Авторизується",
      "largeImageKey": "large",
      "smallImageKey": "small",
      "largeImageText": "NestWorld",
      "smallImageText": "NestWorld",
      "firstButtonEnable": false,
      "firstButtonName": "Site",
      "firstButtonUrl": "https://example.com",
      "secondButtonEnable": false,
      "secondButtonName": "Discord",
      "secondButtonUrl": "https://example.com"
    },
    "authorized": { "...": "той самий формат" },
    "server": {
      "details": "NestWorld Launcher",
      "state": "Переглядає %serverName%",
      "...": "той самий формат"
    },
    "client": {
      "details": "NestWorld Launcher",
      "state": "Грає на %profileName%",
      "...": "той самий формат"
    }
  }
}
```

Секція `server` — опційна: якщо не вказана, при виборі сервера статус лишається таким, як був
у меню ("Вибирає сервер"). Якщо вказана — оновлюється, коли гравець відкриває сторінку
конкретного сервера (`%serverName%` — назва профілю цього сервера), і повертається назад
до `authorized`, коли гравець виходить у меню серверів.

**Якщо `appid` не задано (`0`) — модуль просто тихо вимикається** (лог-повідомлення info,
без помилок і без зупинки лаунчера).

- `details` — перший рядок статусу, після назви застосунку
- `state` — другий рядок статусу
- `largeImageKey` / `smallImageKey` — ім'я картинки (або URL PNG ≥512×512)
- `largeImageText` / `smallImageText` — підпис картинки при наведенні

### Три сценарії (scopes):
- `login` — показується під час завантаження лаунчера, ще без даних користувача
- `authorized` — після авторизації в лаунчері
- `client` — після запуску ігрового клієнта

### Отримання APPLICATION ID:
1. https://discord.com/developers/applications → створити застосунок
2. Скопіювати `APPLICATION ID` в `appid` конфігу модуля

### Аватар користувача
- Якщо `TextureProvider` віддає аватар за URL — доступний плейсхолдер `%avatarUrl%`
  (тільки в `authorized`/`client`, після авторизації)

### Своя картинка під кожен профіль:
У `client.largeImageKey`/`smallImageKey` підставити `%profileUUID%` або `%profileHash%`,
і назвати завантажене в Discord Developer Portal (Rich Presence → Art Assets) зображення
відповідно до UUID/Hash профілю.

---

## Усі плейсхолдери:

- `%uuid%` — UUID користувача
- `%username%` — ім'я користувача
- `%profileVersion%` — версія профілю клієнта
- `%profileName%` — назва профілю (title)
- `%profileUUID%` — UUID профілю
- `%profileHash%` — Hash профілю (UUID без `-`)
- `%launcherVersion%` — версія лаунчера
- `%javaVersion%` / `%javaBits%` — версія/розрядність Java
- `%os%` — операційна система
- `%avatarUrl%` — URL аватара (якщо є TextureProvider)
- `%serverName%` — назва обраного сервера (тільки в `server`)

---

## Консольна команда

`discord` — реєструється в консолі клієнта після розблокування (subcommand-менеджер,
поки без підкоманд — точка розширення на майбутнє).

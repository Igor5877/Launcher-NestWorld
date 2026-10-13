# Дослідження: плагін Azuriom для керування правами гравців лаунчера

Дата: 2026-10-13. Репозиторій: Launcher-NestWorld (форк GravitLauncher 5.6.9), гілка `claude/loving-brahmagupta-ci7cvd`.
Це лише дослідження: жоден файл репозиторію не змінювався, окрім цього звіту.

Умовні позначки: **[К]** підтверджено з коду (репозиторій або клонований код Azuriom), **[П]** припущення / загальне знання Laravel, яке не перевірено в цій сесії.

Джерела Azuriom. Сайт azuriom.com із цього середовища недоступний (DNS `ENOTFOUND`), тому офіційну документацію прочитати не вдалося. Замість неї використано публічний вихідний код, клонований через git-проксі:
- https://github.com/Azuriom/Azuriom (ядро, коміт `ca3ee51`; Laravel 12, PHP ^8.2, `composer.json`);
- https://github.com/Azuriom/Plugin-Shop та https://github.com/Azuriom/Plugin-Vote (реальні плагіни, зразки структури);
- https://github.com/Azuriom/Plugin-SkinAPI (клонується; `AzuriomCommunity/Plugin-SkinAPI` не існує/приватний).

Короткий пошук за запитом «Azuriom plugin GravitLauncher» не знайшов жодного готового плагіна для GravitLauncher. Знайдено лише платний «CentralCorp Panel» (https://market.azuriom.com/resources/163) для іншого лаунчера (AZAuth), без підтвердження сумісності. Готового рішення, яке можна взяти, немає.

---

## 1. Короткий висновок і рекомендована архітектура

**Головне.**
1. Права профілів LaunchServer підтримує повністю: рядки `launchserver.profile.<UUID профілю>.show` та `.enter` у `user_permissions`, а профіль має бути `limited: true`. Це серверна перевірка, її не обійти зміною лаунчера.
2. Права на **окремі моди** в коді вже є, але **лише на боці клієнта** (GUI-лаунчер): `launcher.runtime.optionals.<UUID профілю>.<назва мода малими літерами>.show`, і мод має бути `limited: true`. Це приховування в інтерфейсі, а не захист: сервер усе одно віддає весь каталог профілю (`UpdateResponse`). Якщо мод справді секретний, його треба виносити в окремий профіль з окремим каталогом.
3. Права LaunchServer читає з БД при кожній авторизації, відновленні сесії чи оновленні токена, а далі тримає в об'єкті `Client` до наступної авторизації. Тому зміни діють із наступним входом, відновленням сесії чи оновленням токена. Те, що відбувається при вже відкритому з'єднанні, до кінця не підтверджено (див. п. 7 і 10).

**Рекомендована архітектура.**
- Плагін Azuriom (`launcherperms`) зберігає в БД Azuriom «правила доступу» (суб'єкт: гравець або роль Azuriom; ресурс: профіль або мод) і **сам** синхронізує їх у `user_permissions`.
- Запис іде через окреме Laravel-з'єднання (`DB::connection('launcher')`). Якщо `user_permissions` лежить у тій самій БД, це може бути типове з'єднання.
- Ролі Azuriom (у користувача рівно одна роль, `users.role_id`) за замовчуванням **розгортаються в рядки на рівні гравця** («денормалізований» режим). Тоді плагін не залежить від налаштування таблиці ролей LaunchServer (`rolesTable`) і не ризикує зациклити рекурсивний SQL. Режим із `role.<…>` лишається опцією.
- Каталог профілів і модів плагін отримує з LaunchServer: невеликий **модуль LaunchServer «експорт профілів»** (за зразком `LaunchServer-IslandModule`), який записує список у таблицю БД або надсилає JSON на ендпоінт плагіна з токеном. Ручне вставлення JSON в адмінці лишається запасним варіантом.
- «Керовані» рядки відрізняються від ручних через **таблицю-журнал** (ledger) у БД плагіна плюс суворий білий список форматів рядків. Плагін видаляє лише те, що сам вставив і що відповідає білому списку.

---

## 2. Підтверджені факти про формат LaunchServer

### 2.1. Синтаксис прав (PermissionPattern)
Файл `LauncherAPI/src/main/java/pro/gravit/launcher/base/ClientPermissions.java`:
- Рядок права — довільний текст. Права гравця — список шаблонів, а «запит» — конкретний рядок: `hasPerm(action)` повертає true, якщо **хоч один** шаблон збігається (рядки 49-59). Заборон (deny) немає, є лише дозволи. **[К]**
- Єдиний спецсимвол — `*` («будь-яка кількість будь-яких символів, включно з крапками й порожньо»). Шаблон ділиться за `*` на частини (рядки 110-124). **[К]**
- Збіг (рядки 130-153): без `*` лише точна рівність (`parts.length == 1`, `equals`); з `*` рядок має починатися першою частиною, закінчуватися останньою, а середні частини мають зустрітися по порядку. **[К]**
- Регістр **враховується** (`equals`/`startsWith`/`indexOf`). **[К]**
- Приклади з тесту `LauncherAPI/src/test/java/pro/gravit/launcher/PermissionTest.java`: `*` дозволяє все; `launchserver.*` дозволяє `launchserver.abcd`, але не `nolaunchserver.abcd`; `launchserver.*.prop` не дозволяє `launchserver.ia.prop2`. **[К]**
- Обережно з шаблоном `*` у назвах: рядок права `launchserver.profile.*.show` дає доступ до **всіх** профілів. Плагін має забороняти `*` у будь-якому введенні (розділ 7).
- Дрібна особливість: середні частини шукаються з `offset = pos+1`, а не `pos+len` (рядок 146), тому шаблони з кількома `*` можуть збігатися «з перекриттям». Для наших рядків (максимум без `*`) це не має значення. **[К]**

### 2.2. Доступ до профілів
`LaunchServer/src/main/java/pro/gravit/launchserver/auth/protect/StdProtectHandler.java`:
- `canGetProfile` (рядок 31-33): авторизований клієнт бачить профіль, якщо профіль **не limited**, АБО має право `launchserver.profile.%s.show`. **[К]**
- `canChangeProfile` (рядки 36-38): так само з `launchserver.profile.%s.enter`. Саме `enter` перевіряє `SetProfileResponse` (рядки 29-35) і лише тоді зберігає `client.profile`. **[К]**
- `%s` підставляється двічі (рядки 45-55): спершу `profile.getUUID()` (UUID із дефісами, нижній регістр, бо `UUID.toString()`), потім `profile.getTitle().toLowerCase(Locale.ROOT)`. Достатньо одного з двох. **[К]**
- Старий `profileWhitelist` (назва профілю → список ніків) лишається, але позначений як застарілий (рядки 25-27, 56-57). **[К]**
- `canGetUpdates` (рядок 41-43) **не перевіряє права**: оновлення (файли профілю) дозволені, якщо `client.profile` (вибраний через setProfile) має `dir`/`assetDir`, що збігається з запитаним, або каталог у `allowUpdates`. Тобто захист оновлень = захист `enter`. `UpdateResponse` рядок 22. **[К]**
- Список профілів: `ProfileProvider.getProfiles(client)` (рядки 62-77) і `ProfilesResponse` (рядки 21-26, 40) фільтрують через `canGetProfile`. **[К]**
- Для групових прав `launchserver.profile.<UUID>.*` підтримка є «безкоштовно» завдяки шаблонам (`show` і `enter` одним рядком). Окремого права «.*» код не вводить. **[К]**
- Серверні обгортки (ServerWrapper) отримують `launchserver.profile.<serverName>.show` автоматично (`AuthManager.java` рядки 357-362); їх плагін не зачіпає. **[К]**

### 2.3. Рядки прав для профілів: «публічний» і «обмежений»
- Профіль **публічний**, якщо в його JSON `limited: false` (поле `ClientProfile.limited`, `isLimited()` рядок 386). Тоді права не потрібні, його бачить кожен авторизований. **[К]**
- Профіль **обмежений**, якщо `limited: true`. Тоді потрібна пара прав show+enter. **[К]**
- Прапорець `limited` лежить у JSON-файлі профілю на LaunchServer (каталог `profiles`, `LocalProfileProvider`, рядки 14-27). Плагін Azuriom його **не змінює**: «публічний/обмежений» плагін лише показує (прочитавши з експорту) і підказує, що для обмеження треба вмикати `limited` на стороні LaunchServer. Якщо власник хоче перемикати це з адмінки, потрібне окреме рішення (відкрите питання 4).

### 2.4. SQL: читання прав і ролей
`LaunchServer/src/main/java/pro/gravit/launchserver/auth/core/AbstractSQLCoreProvider.java`:
- Поля конфігурації (рядки 46-53): `permissionsTable`, `permissionsPermissionColumn`, `permissionsUUIDColumn`, `rolesTable`, `rolesNameColumn`, `rolesUUIDColumn`. **[К]**
- Права увімкнені, якщо `permissionsPermissionColumn != null` (рядок 366-368); ролі — якщо `rolesNameColumn != null` (рядок 370-372). **[К]**
- Без ролей (рядки 266-269): `SELECT (<perm>) FROM <permissionsTable> WHERE <uuid> = ?` — просто всі рядки гравця. **[К]**
- З ролями (рядки 253-265) рекурсивний запит:
  ```
  WITH RECURSIVE req AS (
    SELECT p.perm FROM permissions p WHERE p.uuid = ?
    UNION ALL
    SELECT p.perm FROM permissions p
      INNER JOIN roles r ON p.uuid = r.<rolesUUIDColumn>
      INNER JOIN req ON r.<rolesUUIDColumn> = substring(req.perm from 6) or r.name = substring(req.perm from 6)
  ) SELECT * FROM req
  ```
  Тобто для кожного знайденого рядка береться підрядок **з 6-го символу** (тобто відкидається рівно 5 символів, очікується префікс `role.`) і порівнюється з **UUID ролі** АБО з **назвою ролі**; якщо збіглося, підтягуються всі рядки прав, у яких `uuid` = UUID цієї ролі. **[К]**
- Зауваження до коду (потенційні проблеми, для оцінки ризиків):
  1. Префікс `role.` фактично **не перевіряється**: береться будь-який рядок, у якого символи з 6-го збігаються з назвою ролі. Для наших рядків `launchserver.profile…` збігу не буде, але назви ролей, що випадково збігаються, чужі права «підтягнуть». **[К]**
  2. У рядку 261 назва колонки ролей захардкоджена як `r.name`, а не `rolesNameColumn`. Якщо в таблиці ролей колонка називається інакше, запит зламається, поки не задано `customQueryPermissionsByUUIDSQL`. **[К]**
  3. `UNION ALL` без захисту від циклів: якщо роль A успадковує B, а B успадковує A, MySQL перерве рекурсію помилкою `cte_max_recursion_depth` (за замовчуванням 1000; поведінка MySQL, **[П]**). У `queryUser` помилка ловиться (`getUserByUUID` повертає null, лог `SQL error`), тобто **гравець не зможе увійти**. Плагін мусить заборонити цикли. **[К]/[П]**
  4. `WITH RECURSIVE` потребує MySQL 8.0+ / MariaDB 10.2.2+ **[П]**.
  5. Дублікати рядків нічого не ламають (результат — список; `hasPerm` просто перебирає шаблони), та `UNION ALL` їх не прибирає. Унікальності LaunchServer не вимагає. **[К]**
- Список ролей гравця (рядки 263-265) бере ролі, на які посилаються рядки `role.<…>` гравця (лише прямі). Ці назви потрапляють у `ClientPermissions.roles`. **[К]**
- Лише для гравця, що має Azuriom-роль, `AzuriomCoreProvider.enrichUserWithAzuriomData` (рядки 383-390) **додає назву ролі Azuriom у список `roles`** (для `hasRole`/`getMainRole`), але **не додає прав**. Тобто сама по собі роль Azuriom жодних прав профілів не дає. **[К]**

### 2.5. Формат UUID у БД
- `queryUser` (рядки 333-347): після знаходження користувача права запитуються за `user.uuid.toString()` — тобто **UUID із дефісами, нижній регістр**. Отже, `user_permissions.<uuid>` має містити **UUID із дефісами** (`xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`). Рядок без дефісів НЕ збігся б. **[К]** Це найважливіший формат для плагіна.
- Таблиця користувачів: `getUserByUUID` (рядки 85-95) шукає спочатку за UUID без дефісів (`replace("-", "")`), потім із дефісами (коміти `8ae4c22`, `70b007e`). `toUUID` (рядки 313-320) розбирає і 32-символьний, і з дефісами. Тобто `users.game_id` в Azuriom (без дефісів) читається коректно. **[К]**
- Порівняння рядкових UUID у MySQL залежить від collation (за замовчуванням нечутливе до регістру), але плагіну краще завжди писати **нижній регістр із дефісами**, щоб збігатися з тим, що формує Java. **[К]/[П]**
- Зверніть увагу: `updateAuth`/`updateServerID` пишуть у таблицю користувачів UUID **з дефісами** (`user.getUUID().toString()`, рядки 283, 297), тоді як `game_id` в Azuriom без дефісів. Це окрема проблема авторизації, до плагіна прав не належить (відмічаю як побічну знахідку; потребує перевірки на живій системі, бо залежить від `customUpdateAuthSQL`). **[К] (код), не перевірено в роботі.**

### 2.6. Як обчислюється UUID гравця в Azuriom (для перевірки)
- Реєстрація: `RegisterController.php:93` — `game()->getUserUniqueId($name)`. **[К]**
- Для «Minecraft offline» (`app/Games/Minecraft/MinecraftOfflineGame.php`, рядки 31-43): UUID v3 з простором імен NIL, власний генератор `md5($name, true)` від рядка `"OfflinePlayer:" . $name`, потім дефіси видаляються. Це те саме, що `UUID.nameUUIDFromBytes("OfflinePlayer:<нік>")` у Java (використовується й у `AzuriomCoreProvider.createOfflineSession`, рядок 147). **[К]**
- **Нік НЕ приводиться до нижнього регістру** (на відміну від того, що припускалося в завданні): хеш береться від ніка як є. Нижній регістр має лише hex-запис результату. Тому плагін не повинен перераховувати UUID за ніком; треба читати `users.game_id`. **[К]**
- `game_id` не редагується в адмінці (поле `disabled`, `resources/views/admin/users/edit.blade.php:228-231`), а при перейменуванні користувача не перераховується (у валідації `name` немає зв'язку з `game_id`, `app/Http/Requests/UserRequest.php`). **[К]**
- Якщо `game_id` порожній: API-логін лаунчера (`app/Http/Controllers/Api/AuthController.php:87-89`) записує туди **випадковий** `Str::uuid()` (з дефісами!). Тому `game_id` може бути з дефісами або без, а до першого входу через лаунчер — `NULL`. Плагін має нормалізувати обидва формати й не писати рядки для `NULL` (див. розділ 5). **[К]**

### 2.7. Права на моди (optional files)
`LauncherAPI/.../profiles/optional/OptionalFile.java`: поля `name`, `info`, `category`, `visible`, `mark` (увімкнений за замовчуванням), `limited`, `actions`, `triggersList`, залежності/конфлікти/групи. **[К]**
- Тригери (`OptionalTrigger.registerProviders`, рядки 14-21) лише `java`, `os`, `arch`. Тригера `permission` немає. `OptionalTriggerContext.getPermissions()` існує (рядок 15), але ні один тригер ним не користується. **[К]**
- **Механізм, що вже є** — у GUI-лаунчері `LauncherRuntime-4.0.6/.../gui/impl/TriggerManager.java` (рядки 22-33):
  ```java
  if (optional.limited) {
      if (!authService.checkPermission("launcher.runtime.optionals.%s.%s.show"
              .formatted(profile.getUUID(), optional.name.toLowerCase(Locale.ROOT)))) {
          view.disable(optional, null);  optional.visible = false;
      } else { optional.visible = true; }
  }
  ```
  Тобто для мода з `limited: true` потрібне право `launcher.runtime.optionals.<UUID профілю з дефісами>.<назва мода малими літерами>.show`. `checkPermission` бере `rawAuthResult.permissions.hasPerm` (`gui/service/AuthService.java:118-123`), тобто права, які сервер віддав при авторизації (`AuthResponse.java:55`). **[К]**
- Виклики: `ProfilesService.setProfilesResult` (рядки 51-62, при отриманні списку профілів) і кнопка «зберегти» в `SettingsScene` (рядок 125). **[К]**
- **Обмеження (важливо):**
  1. Це **лише клієнтська** перевірка. Сервер не фільтрує `OptionalFile` за правами; `UpdateResponse` віддає весь `HashedDir` профілю. Модифікований лаунчер може завантажити «закритий» мод. **[К]**
  2. `optional.visible = true` для дозволених модів перезаписує `visible: false` із JSON (рядок 31), тобто технічний прихований мод із `limited: true` стане видимим. **[К]**
  3. Якщо «закритий» мод є залежністю іншого дозволеного мода, `OptionalView.enable/fixDependencies` може його знову ввімкнути (рядки 80-89, 113-136 `OptionalView.java`). **[К]** Так само обов'язковий тригер `required` у `TriggerManager` (рядки 45-51) виконується **після** перевірки `limited` і може знову ввімкнути мод. **[К]**
  4. Назва мода в праві береться з `name` у нижньому регістрі; пробіли дозволені, `*` ні. Назви мають бути унікальні в межах профілю (`equals` за `name`, `OptionalFile.java:44-50`). **[К]**
- Висновок: механізм для модів **існує, але лише для GUI-лаунчера та лише як приховування**. Рядок: `launcher.runtime.optionals.<profileUUID>.<modname>.show`. Підтверджено кодом; перевірка в роботі не проводилась.

Варіанти для справжнього захисту модів:
  - **A (рекомендовано для «секретних» модів):** окремий профіль на набір модів з **власним каталогом (`dir`)**, `limited: true`. Тоді працює серверний `show`/`enter`; обхід неможливий.
  - **B:** використати клієнтське право (як є) для «м'якого» розмежування, коли витік не страшний (косметичні/необов'язкові моди).
  - **C (потребує змін коду):** власний тригер `permission` у LauncherAPI (реєстрація в `OptionalTrigger.registerProviders`) плюс **серверна фільтрація** в `UpdateResponse`: перед відправкою вилучати з `HashedDir` файли `OptionalActionFile`, недоступні гравцеві (є `disableInHashedDir`, `OptionalActionFile.java`). Складно: і лаунчер, і сервер.
  - **D:** плагін-обгортка/модуль LaunchServer, який при формуванні `UpdateRequestEvent` прибирає недоступні моди. Той самий обсяг, що й C.

### 2.8. Коли права читаються і чи кешуються
- Права вантажаться SQL-запитом у `queryUser` (рядок 344) при **кожному** `getUserByUUID/ByUsername/ByLogin`. Ці методи викликаються при логіні (`authorize`), при відновленні сесії за токеном (`RestoreResponse.java:69` → `getUserSessionByOAuthAccessToken`), при оновленні токена (`refreshAccessToken`) і при `AzuriomCoreProvider.reportFromOAuth`. **[К]**
- Далі результат записується в `Client.permissions` (`AuthManager.internalAuth`, рядок 186; `RestoreResponse` рядок 82) і використовується в `ProfilesResponse`/`SetProfileResponse` без нових запитів до БД. Окремого кешу прав на сервері немає (кеш `verifyCache` в `AzuriomCoreProvider` 30 с стосується токена Azuriom, не прав; рядки 48-51). **[К]**
- Висновок: зміна в `user_permissions` потрапляє до гравця при **наступній авторизації/відновленні сесії/оновленні токена** (години, а за замовчуванням `expireSeconds` = 1 година, рядок 40). Гравці, що вже в грі, не втрачають гру (для запущеного клієнта права вже не перевіряються), але вже відкритий лаунчер побачить зміни лише після повторного входу чи оновлення сесії. Точний момент повторного запиту профілів у GUI не досліджувався. **[К] (серверна частина) / не підтверджено (клієнтська).**

### 2.9. Приклади рядків
`<uuid>` = UUID гравця з дефісами малими літерами; `<P>` = UUID профілю (також з дефісами).

| № | Призначення | Таблиця `user_permissions` (uuid → permission) |
|---|---|---|
| 1 | Гравець бачить профіль | `<uuid>` → `launchserver.profile.<P>.show` |
| 2 | Гравець може обрати/оновити профіль | `<uuid>` → `launchserver.profile.<P>.enter` |
| 3 | Те саме одним рядком (шаблон; плагін так не пише) | `<uuid>` → `launchserver.profile.<P>.*` |
| 4 | Профіль за назвою (альтернатива UUID) | `<uuid>` → `launchserver.profile.nestworld.show` (назва малими літерами) |
| 5 | Роль → профіль, режим «успадкування» | `<roleUuid>` → `launchserver.profile.<P>.show` і `.enter`; гравець: `<uuid>` → `role.<roleUuid>` (або `role.<назва ролі>`) |
| 6 | Роль → профіль, «денормалізований» режим (рекомендовано) | для кожного гравця ролі: `<uuid>` → `launchserver.profile.<P>.show` і `.enter` |
| 7 | Гравець → мод (клієнтське право) | `<uuid>` → `launcher.runtime.optionals.<P>.sodium.show` (назва мода `Sodium` → `sodium`) |
| 8 | Публічний профіль | рядків немає; у JSON профілю `"limited": false` |
| 9 | Адмін-суперправо (плагін ніколи не пише!) | `<uuid>` → `*` |

Що **не** вдалося підтвердити в коді цього репозиторію: реальний `config`-розділ LaunchServer (`LaunchServer.json`/`sql` секція) із назвами таблиць і колонок; чи є колонка-маркер у `user_permissions`; реальна структура таблиці ролей (див. розділ 10).

Що буде при видаленні рядка: LaunchServer нічого не кешує постійно, тож право зникає при наступному читанні (розділ 2.8). Видалення рядка `role.<…>` відключає все успадковане від ролі. Видалення рядків ролі прибирає права в усіх, хто на неї посилається. **[К]**

---

## 3. Що відомо про розробку плагінів Azuriom (з джерелами)

Усе нижче **[К]** з вихідного коду, якщо не вказано інакше. Базові джерела: ядро https://github.com/Azuriom/Azuriom (`app/Extensions/Plugin/*`, `app/Console/Commands/PluginCreateCommand.php` та `stubs/plugin/*`), плагіни Shop/Vote.

### 3.1. Структура плагіна
Команда `php artisan plugin:create <name>` (`PluginCreateCommand.php`) створює:
```
plugins/<id>/
  plugin.json            # id, name, description, version, url, authors, azuriom_api, providers[]
  composer.json          # type "azuriom-plugin", autoload psr-4 "<Namespace>\\": "src/"
  src/Providers/<Name>ServiceProvider.php   # extends BasePluginServiceProvider
  src/Providers/RouteServiceProvider.php    # extends BaseRouteServiceProvider
  src/Controllers/Admin/*.php, src/Controllers/Api/*.php
  src/Models/
  routes/{web,admin,api}.php
  resources/views/admin/*.blade.php, resources/lang/<locale>/*.php
  database/migrations/
  assets/{css,js}/
```
- `plugin.json` поля: `id`, `name`, `description`, `version`, `url`, `authors[]`, `azuriom_api` (стаб створює `1.1.0`; ядро приймає `1.0.0`, `1.1.0`, `1.2.0`, `app/Extensions/ExtensionManager.php:10`), `providers[]` (два класи). Shop використовує `azuriom_api: 1.2.0`. **[К]**
- Провайдери створюються менеджером у `PluginManager::loadPlugins` (рядки 75-107). У проді плагін, що кидає виняток при завантаженні, **автоматично вимикається** (рядки 100-106) — тестуйте на dev-середовищі. **[К]**
- Поточні версії: Laravel 12, PHP ^8.2 (`composer.json` ядра). **[К]**

### 3.2. ServiceProvider
`BasePluginServiceProvider` (`app/Extensions/Plugin/BasePluginServiceProvider.php`) дає: `loadViews()` (простір імен вʼю = `plugin->id`, наприклад `launcherperms::admin.index`), `loadTranslations()`, `loadMigrations()` (з `database/migrations`), `registerAdminNavigation()` → викликає ваш `adminNavigation()`, `registerRouteDescriptions()`, `registerSchedule()` + метод `schedule(Schedule $schedule)`, `registerMiddleware()`, `registerPolicies()`. **[К]**

Формат пункту меню (Vote: `VoteServiceProvider.php:85-102`; Shop: `ShopServiceProvider.php:143-…`):
```php
protected function adminNavigation(): array {
    return ['launcherperms' => [
        'name' => trans('launcherperms::admin.nav.title'),
        'type' => 'dropdown',                 // або без type — простий пункт з 'route'
        'icon' => 'bi bi-shield-lock',
        'route' => 'launcherperms.admin.*',
        'permission' => 'launcherperms.manage',
        'items' => [
            'launcherperms.admin.access.index' => trans('launcherperms::admin.nav.access'),
            'launcherperms.admin.overview'     => trans('launcherperms::admin.nav.overview'),
        ],
    ]];
}
```
Меню рендериться в `resources/views/admin/layouts/admin.blade.php:225-262` (перевірка `permission` через `Gate`). **[К]**

### 3.3. Маршрути, права, middleware
- `RouteServiceProvider` плагіна (стаб): `Route::middleware('admin-access')->prefix('admin/'.$id)->name($id.'.admin.')->group(plugin_path($id.'/routes/admin.php'))`. **[К]**
- Група `admin-access` (`app/Http/Kernel.php:50-55`): `web`, `auth`, `can:admin.access`, `EnsureTwoFactorAuthEnabled`. Тобто CSRF (група `web`) і авторизація забезпечені автоматично. **[К]**
- Власні права: `Permission::registerPermissions(['vote.admin' => 'vote::admin.permission'])` у `boot()` (Vote `:38-40`), потім у маршрутах `Route::middleware('can:vote.admin')->group(...)` (Vote `routes/admin.php`). Адміністратор ролі (`is_admin`) проходить усі перевірки (`AppServiceProvider.php:60-63`). **[К]** Права призначаються на сторінці ролей адмінки.
- Контролер: `Azuriom\Http\Controllers\Controller`; вʼю `@extends('admin.layouts.admin')`, `@section('title')`, `@section('content')`; стеки `@push('scripts')` (у `<head>`) і `@push('footer-scripts')` (кінець body) (`admin.blade.php:17-18, 467`; приклад Shop `elements/select.blade.php`). **[К]**

### 3.4. Міграції
Звичайні Laravel-міграції в `database/migrations`, `Schema::create(...)` (приклад Vote `2019_12_31_000000_create_vote_sites_table.php`). Підключаються `loadMigrations()`; виконуються з адмінки при встановленні/оновленні плагіна **[П]** (механізм запуску не перевірявся). **[К]/[П]**

### 3.5. Лог дій
`ActionLog::log('launcherperms.rule.updated', $model, $data)` (`app/Models/ActionLog.php:179`) та реєстрація текстів `ActionLog::registerLogs(key, ['icon'=>…,'color'=>…,'message'=>…])` / `registerLogModels` (рядки 199-239; Vote робить це в `boot()`). Дії видно на сторінці логів адмінки. **[К]** Це готовий журнал аудиту.

### 3.6. Моделі, події, спостерігачі
- `User` (`app/Models/User.php`): `game_id` (string|null), `role_id`; зв'язок `role()` (одна роль); `isAdmin()` = `role->is_admin`. `Role`: `id, name, color, power, is_admin`, **немає uuid**, є `permissions()`. **[К]**
- Події Eloquent: `User::observe(UserObserver::class)` (Shop `ShopServiceProvider.php:114`, спостерігач `deleted(User $user)` у `Observers/UserObserver.php`). Ядро саме реагує на зміну ролі в `User::booted()` через `updated` + `isDirty('role_id')` (рядки 130-149). У власному спостерігачі `updated` використовуйте `$user->wasChanged('role_id')`. **[К]/[П]** (`wasChanged` — стандартний Eloquent).
- Масові видалення/оновлення через query builder **не викликають** подій Eloquent. Тому потрібна страхувальна команда звірки (розділ 5).
- Розширення сторінки редагування користувача: `View::composer('admin.users.edit', MyComposer::class)`, де `MyComposer extends AdminUserEditComposer` і `getCards()` повертає `['launcher' => ['name'=>…, 'view'=>'launcherperms::admin.user-card']]` (`app/Extensions/Plugin/AdminUserEditComposer.php`; приклад Shop `ShopAdminUserComposer.php`). Зручно показати картку «Доступи лаунчера» прямо в профілі гравця. **[К]**
- Планувальник: `registerSchedule()` + `schedule()` (Vote `VoteServiceProvider`), а команди `$this->commands([...])`. **[К]**

### 3.7. Інша БД
- Ядро використовує звичайні Laravel-з'єднання (`config/database.php`, секція `mysql` з `DB_HOST` тощо, рядки 46-64). Плагін може додати своє з'єднання у `boot()` через `config(['database.connections.launcher' => [...]])` і користуватись `DB::connection('launcher')`. Приклад у Shop/Vote не знайдено (вони працюють лише з основною БД). **[П]** (стандартний Laravel; на практиці перевірити на тестовому Azuriom).
- Налаштування плагіна зберігати через `Setting::updateSettings('launcherperms.db_host', …)` та читати `setting('…')` (`app/Models/Setting.php:114`, `setting()` використовується в `User.php`). Паролі — `Setting::markAsEncrypted(...)` (рядок 101). **[К]**

### 3.8. JS/CSS в адмін-панелі
- Адмінка (AdminKit 3.5, Bootstrap 5, `bootstrap-icons`, `petite-vue`, `axios`, `flatpickr`, `sortablejs`, `chart.js`; `package.json`, `resources/js/admin/admin.js`). **Select2/Tom Select у ядрі немає.** `petite-vue` автоматично монтується на елементи з `v-scope` (`admin.js`, розділювачі `$delimiters: ['\u0000','\u0001']`, тобто mustache-інтерполяцію `{{ }}` відключено — використовуйте `v-text`). **[К]**
- Пошукове випадаюче: варіанти — (a) `bootstrap-select` із CDN, як робить Shop (`resources/views/admin/elements/select.blade.php`, jsdelivr + jQuery), (b) Tom Select з CDN (вимагає фіксації версії), (c) власний «combobox» на `petite-vue` з `<input>` + список з фільтрацією — без залежностей, надійніший (немає CDN/CSP-ризиків). **Рекомендація: (c)**, а для запасного варіанту — Tom Select.
- Підключення власних ресурсів: `assets/js/*.js` плагіна, URL через `plugin_asset('launcherperms', 'js/combobox.js')` (`app/helpers.php:230`). **[К]** Увага: у новому ядрі vendor-пакети збираються у `public/assets/vendor` (`vite.config.js:6-8`), а плагіни Shop/Vote посилаються на `asset('vendor/...')`; шлях залежить від версії Azuriom, тому свої залежності краще класти у `assets/` плагіна. **[К]**

---

## 4. Звідки брати профілі й моди

Сайт Azuriom не бачить каталог LaunchServer. Потрібні: UUID профілю, назва, версія, `limited`, `dir`, список `updateOptional` (назва, категорія, info, `limited`, `visible`, `mark`). Усе є в JSON `profiles/*.json` (`ClientProfile`, `OptionalFile`).

Корисні факти про LaunchServer:
- `NettyWebAPIHandler` (`.../socket/handlers/NettyWebAPIHandler.java`) — лише реєстр сервлетів (`addNewSeverlet("/webapi/<path>")`, рядки 39-48), **жодного сервлета за замовчуванням немає** (`grep` по репозиторію: єдині згадки — самі методи) і **немає вбудованої авторизації** — токен треба реалізувати самому. **[К]**
- Подія `LaunchServerProfilesSyncEvent` (`modules/events`) викликається **лише один раз при старті** (`LaunchServer.java:372`), а **не** всередині `syncProfilesDir()` (рядки 404-410), який запускають команди `syncprofiles`, `makeprofile`, `saveprofiles` тощо. Тобто готового хука «після кожної зміни профілів» немає; для експорту після ручних команд потрібна або власна команда, або мінімальна правка `syncProfilesDir()` (викликати подію/експорт). **[К]**
- Зразок модуля LaunchServer: `LaunchServer-IslandModule` (`IslandModule.java`: `LauncherModule`, `registerEvent(..., LaunchServerFullInitEvent.class)`, вихідні HTTP-запити через `HttpURLConnection`, збірка `build.gradle` із `Module-Main-Class`). **[К]**

| Варіант | Плюси | Мінуси / ризики | Складність |
|---|---|---|---|
| **1. Модуль LaunchServer пише список профілів і модів у таблицю БД (чи JSON-файл), плагін читає** | Автоматично, актуально; не потрібні відкриті порти LaunchServer; БД уже спільна; безпека на рівні прав БД | Потрібен Java-модуль і хук на зміну профілів (див. вище); потрібна окрема таблиця; доступ запису модуля до БД | Середня |
| **2. HTTP-ендпоінт LaunchServer (`/webapi/…`) з токеном** | Не залежить від БД; плагін робить запит «за потребою» | Власний сервлет + свій токен/rate-limit; відкриває службовий порт назовні; сайт і LaunchServer мають бачити один одного; ризик витоку списку прихованих профілів | Середня-висока |
| **3. Ручне завантаження JSON профілю в адмінці** | Мінімум коду на стороні LaunchServer; працює відразу; зручний запасний варіант | Людський фактор, розсинхрон, потрібно повторювати при кожній зміні модів; не підходить для регулярного використання | Низька |
| **4. Спільний каталог `profiles` на веб-сервері** | Просто, якщо все на одній машині; плагін парсить JSON | Різні машини/Docker — складно; плагіну потрібен доступ до файлів (права, `open_basedir`); профілі містять `dir`, аргументи JVM — чутливі дані | Низька-середня |

**Рекомендація:** варіант 1 (Java-модуль «експорт профілів», який або записує в таблицю `launcher_profiles_export`, або надсилає POST на закритий API-маршрут плагіна з токеном — вибрати за топологією), плюс варіант 3 як резерв (імпорт JSON в адмінці з валідацією). Варіант 2 лише якщо БД недоступна для запису з LaunchServer. Експортер викликається: при старті, командою `exportprofiles`, і (за бажанням) після `syncProfilesDir()`.

Формат експорту (мінімум): `[{uuid, title, version, dir, limited, sortIndex, optionals:[{name, category, info, limited, visible, mark}]}]`. Секрети (`jvmArgs`, `clientArgs`, `servers`) не експортувати.

---

## 5. Модель даних і алгоритм синхронізації

### 5.1. Таблиці плагіна (БД Azuriom, міграції)
```
launcher_profiles_cache   -- копія експорту (або імпортованого JSON)
  id, profile_uuid (char36, unique), title, version, dir, limited bool, payload json, synced_at

launcher_mods_cache
  id, profile_uuid, mod_key (lower(name)), name, category, limited bool, visible bool, mark bool
  unique(profile_uuid, mod_key)

launcher_access_rules      -- правила, які задає адміністратор
  id,
  subject_type enum('user','role'),        -- гравець або роль Azuriom
  subject_id   unsigned int,               -- users.id або roles.id (НЕ UUID!)
  resource_type enum('profile','mod'),
  profile_uuid char36,
  mod_key varchar null,                    -- null для profile
  created_by unsigned int, created_at
  unique(subject_type, subject_id, resource_type, profile_uuid, mod_key)

launcher_managed_rows      -- журнал (ledger) рядків, вставлених плагіном
  id, player_uuid char36, permission varchar(255),
  unique(player_uuid, permission)

launcher_sync_log          -- для аудиту (або ActionLog)
```
Правила зберігаються за **`users.id` / `roles.id`**, а не за UUID, тому перейменування, відсутній `game_id` та зміна ролі не ламають правил. «Публічний, усім» для профілю — не правило, а стан (`limited=false` з експорту), UI показує його позначкою без жодних рядків.

### 5.2. Обчислення бажаних рядків
```
desired[player_uuid] = ∅
for user in users where game_id not null:
    uuid = normalizeUuid(user.game_id)          # 32 hex -> з дефісами, lower; інакше lower($v) якщо валідний UUID
    rules = rules(subject=user.id) ∪ rules(subject=role(user.role_id))
    for rule in rules:
        if rule.resource=profile and profile.limited:   add show, enter
        if rule.resource=mod  and mod.limited:          add launcher.runtime.optionals.<P>.<mod_key>.show
        # мод у не-limited профілі/моді: рядок не потрібен, UI попереджає
```
Генерація рядка — лише з **білого списку шаблонів** і лише зі значень, що існують у кеші профілів (UUID перевіряється регулярним виразом, `mod_key` не містить `*`, `%`, керуючих символів).

### 5.3. Ідемпотентна синхронізація
Псевдокод (PHP-скелет нижче). Для кожного гравця (або для всіх при повній звірці), у транзакції на з'єднанні `launcher`:
1. `current` = рядки `user_permissions` гравця; `ledger` = рядки з `launcher_managed_rows` (БД Azuriom).
2. `toInsert = desired − current`. Рядки, що вже існують **не від плагіна** (були вручну), не записуються і не заносяться в ledger (щоб не видалити чуже).
3. `toDelete = (ledger − desired) ∩ current ∩ whitelist`. Видаляємо ЛИШЕ їх.
4. Вставка в `user_permissions`, зміни ledger і запис у лог.
5. Нетривіальний момент: дві БД (Azuriom і LaunchServer) можуть бути різними серверами — єдиної транзакції між ними нема. Порядок: спочатку операції в `user_permissions`, потім ledger; операція ідемпотентна, тож повтор усуває розбіжність (ledger-рядок без реального рядка ігнорується, реальний без ledger — «сирота», показуємо в UI «знайдено ручні рядки такого формату»).
6. Кнопка/команда «Повна звірка» (`php artisan launcherperms:reconcile`) знаходить: гравців, яких видалили (їх рядки із ledger), змінений `game_id`, зміну ролей масовими запитами, і виправляє. Запускати за розкладом (`registerSchedule`).

Режим ролей «успадкування» (опція): рядок `role.<roleKey>` для гравця, права самої ролі — під її UUID у `user_permissions`, потребує таблиці ролей LaunchServer (`rolesTable`, `rolesUUIDColumn`, `rolesNameColumn`, не забути про `r.name` у запиті, п. 2.4) та заборони циклів. За замовчуванням **вимкнено**.

Події:
- зміна ролі гравця → `User::updated` + `wasChanged('role_id')` → `syncUser($user)`;
- видалення → `User::deleted` → прибрати ledger-рядки гравця з `user_permissions`;
- перейменування → нічого (UUID не змінюється);
- `game_id` змінився/з'явився (перший вхід через лаунчер) → `updated` + `wasChanged('game_id')` → перенести рядки зі старого UUID на новий;
- зміна правила/ролі в UI → `syncSubject`;
- оновлення кешу профілів → перерахунок усіх, кого стосується (також видалити рядки на зниклі профілі).

### 5.4. PHP-скелет (псевдокод; сигнатури Azuriom підтверджені в п. 3, решта — Laravel)
```php
namespace Azuriom\Plugin\LauncherPerms\Services;

use Azuriom\Models\User;
use Illuminate\Support\Facades\DB;

final class PermissionFormat {
    public static function uuid(?string $gameId): ?string {
        if ($gameId === null) return null;
        $h = strtolower(str_replace('-', '', trim($gameId)));
        if (!preg_match('/^[0-9a-f]{32}$/', $h)) return null;
        return substr($h,0,8).'-'.substr($h,8,4).'-'.substr($h,12,4).'-'.substr($h,16,4).'-'.substr($h,20);
    }
    public static function profileShow(string $p): string  { return "launchserver.profile.$p.show"; }
    public static function profileEnter(string $p): string { return "launchserver.profile.$p.enter"; }
    public static function modShow(string $p, string $modKey): string {
        return "launcher.runtime.optionals.$p.$modKey.show";
    }
    // Білий список: тільки ці формати плагін має право писати/видаляти
    public static function isManagedFormat(string $perm): bool {
        return (bool) preg_match(
          '/^(launchserver\.profile\.[0-9a-f-]{36}\.(show|enter)|launcher\.runtime\.optionals\.[0-9a-f-]{36}\.[^*\x00-\x1f]{1,100}\.show)$/', $perm);
    }
}

final class PermissionSync {
    public function __construct(private string $conn, private string $table,
                                private string $uuidCol, private string $permCol) {}

    public function syncUser(User $user): void {
        $uuid = PermissionFormat::uuid($user->game_id);
        $desired = $uuid ? $this->desiredFor($user) : [];       // array<string>
        $this->apply($user->id, $uuid, $desired);
    }

    private function apply(int $userId, ?string $uuid, array $desired): void {
        if ($uuid === null) return;                              // немає game_id -> нічого не пишемо
        $db = DB::connection($this->conn);
        $db->transaction(function () use ($db, $uuid, $desired) {
            $current = $db->table($this->table)->where($this->uuidCol, $uuid)
                          ->pluck($this->permCol)->all();
            $ledger  = ManagedRow::where('player_uuid', $uuid)->pluck('permission')->all();

            $insert = array_values(array_diff($desired, $current));
            $delete = array_values(array_filter(
                array_intersect(array_diff($ledger, $desired), $current),
                [PermissionFormat::class, 'isManagedFormat']));

            foreach ($insert as $p) {
                $db->table($this->table)->insert([$this->uuidCol => $uuid, $this->permCol => $p]);
                ManagedRow::firstOrCreate(['player_uuid' => $uuid, 'permission' => $p]);
            }
            if ($delete) {
                $db->table($this->table)->where($this->uuidCol, $uuid)
                   ->whereIn($this->permCol, $delete)->delete();
                ManagedRow::where('player_uuid', $uuid)->whereIn('permission', $delete)->delete();
            }
        });
    }
}
```
Примітка: у `user_permissions` можуть бути додаткові NOT NULL колонки (id, timestamps); фактичну схему треба уточнити (розділ 9). Імена таблиці/колонок беруться з налаштувань плагіна (за аналогією з `permissionsTable`, `permissionsUUIDColumn`, `permissionsPermissionColumn` у LaunchServer) і **перевіряються регулярним виразом `^[A-Za-z0-9_]+$`**, перш ніж потрапити в запит.

---

## 6. Ескіз інтерфейсу

Екрани (всі за правом `launcherperms.manage`, меню «Доступи лаунчера»):
1. **Доступи** (головний екран). Угорі combobox «Кому»: пошук серед гравців (нік, можна по частині) і ролей Azuriom (група «Ролі», колір ролі). Далі дерево: профіль (чекбокс) → список його модів (чекбокси). Поряд з кожним профілем бейдж:
   - «Публічний, усім» (сірий, чекбокс заблокований і пояснення: доступ без обмежень, рядки не потрібні);
   - «Обмежений» (чекбокс активний);
   - «Невідомий (зник з LaunchServer)» (червоний).
   Моди: бейдж «публічний» (limited=false: галочка неактивна) або «тільки вибраним (приховується в лаунчері)». Біля кожного обмеженого мода іконка-підказка: «Це приховування, а не захист: для секретних модів зробіть окремий профіль».
   Внизу «Зберегти» і попередній перегляд «Буде додано: N, видалено: M» (список рядків у розгорнутому блоці для технічних користувачів).
2. **Хто що має**. Перемикач «за профілем / за модом»: вибір профілю (або мода) → таблиця: гравці, ролі, джерело («прямо» або «через роль X»), дата й хто додав. Кнопка «Відкликати».
3. **Профілі й моди** (каталог): дата останньої синхронізації, кнопка «Оновити з LaunchServer» / «Імпортувати JSON».
4. **Журнал**: або вбудований журнал Azuriom (`ActionLog`), або вкладка зі списком змін (хто, кому, що, коли).
5. Картка «Доступи лаунчера» на сторінці користувача (`AdminUserEditComposer`) з посиланням на екран 1.

Мінімальний фрагмент розмітки (Blade + petite-vue; `{{ }}` у шаблонах JS відключено, тому `v-text`):
```blade
@extends('admin.layouts.admin')
@section('title', trans('launcherperms::admin.access.title'))
@section('content')
<form method="POST" action="{{ route('launcherperms.admin.access.save') }}" v-scope="accessForm(@js($subjects), @js($catalog), @js($rules))">
  @csrf
  <input type="hidden" name="subject" :value="subject">
  <div class="mb-3 position-relative">
    <input class="form-control" v-model="q" @focus="open=true" placeholder="Гравець або роль...">
    <ul class="list-group position-absolute w-100" v-show="open" style="z-index:5;max-height:260px;overflow:auto">
      <li class="list-group-item list-group-item-action" v-for="s in filtered" @click="pick(s)" v-text="s.label"></li>
    </ul>
  </div>
  <div v-for="p in catalog">
    <label><input type="checkbox" :disabled="!p.limited" v-model="sel[p.uuid]"> <b v-text="p.title"></b>
      <span class="badge" :class="p.limited ? 'bg-warning' : 'bg-secondary'" v-text="p.limited ? 'Обмежений' : 'Публічний, усім'"></span></label>
    <div class="ms-4" v-for="m in p.mods"><label><input type="checkbox" :disabled="!m.limited" v-model="selMod[p.uuid+':'+m.key]"> <span v-text="m.name"></span></label></div>
  </div>
  <input type="hidden" name="profiles" :value="JSON.stringify(sel)">
  <button class="btn btn-primary">Зберегти</button>
</form>
@endsection
@push('footer-scripts')
<script>
function accessForm(subjects, catalog, rules){ return { subjects, catalog, q:'', open:false, subject:null, sel:{}, selMod:{},
  get filtered(){ const s=this.q.toLowerCase(); return this.subjects.filter(x=>x.label.toLowerCase().includes(s)).slice(0,50) },
  pick(s){ this.subject=s.id; this.q=s.label; this.open=false; this.sel=rules[s.id]?.profiles||{}; this.selMod=rules[s.id]?.mods||{} } } }
</script>
@endpush
```
(Гравців потенційно тисячі — список підвантажувати через AJAX `/search?q=` з обмеженням 50.) Складність UI: середня; найбільше роботи в екрані 2 та в зручності пошуку. Для нетехнічного адміністратора: ніяких сирих рядків за замовчуванням, зрозумілі назви, бейджі, підказки та попередній перегляд змін.

---

## 7. Безпека і надійність

- **Доступ.** Маршрути в групі `admin-access` (`auth`, `admin.access`, примусова 2FA за налаштуванням) плюс окреме право `launcherperms.manage` (`Permission::registerPermissions`). Лише ролі з ним (і `is_admin`) бачать сторінки. **[К]**
- **CSRF.** Групи `web` (в `admin-access`) містять CSRF; у формах `@csrf`; для AJAX додати заголовок `X-CSRF-TOKEN` (axios у адмінці налаштований на `X-Requested-With`, `admin.js`). **[К]/[П]**
- **Валідація.** Адміністратор **ніколи не вводить рядок права**. Усі рядки будує `PermissionFormat` з ідентифікаторів: UUID профілю (regex + існує в кеші), `mod_key` (існує в кеші, без `*`). Білий список + заборона `*` унеможливлюють запис `*`, `launchserver.*`, `launcher.*`. Додатково `isManagedFormat` перед кожною вставкою/видаленням. **[К]** (логіка від PermissionPattern, п. 2.1)
- **SQL-ін'єкції.** Лише query builder / prepared statements. Імена таблиць/колонок (налаштування) — лише через regex `^[A-Za-z0-9_]+$`. Експортований від LaunchServer JSON — недовірені дані: перевіряти типи та довжину.
- **Захист від скалічення реальних прав:** не чіпати рядки, яких немає в ledger; не писати для `game_id = NULL`; ніколи не торкатись рядків із `*`, `role.`, `launchserver.management`, `launchserver.checkserver` тощо (поза білим списком). Адмінські права LaunchServer лишаються ручними.
- **Аудит.** `ActionLog::log(...)` на кожну зміну (хто, кому, діф). Додатково локальний `launcher_sync_log`. **[К]**
- **Резервна копія перед масовою зміною.** Перед повною звіркою або зміною правила ролі, що зачіпає >N гравців: зберегти знімок рядків `user_permissions`, що стосуються керованих форматів (таблиця `launcher_perm_backup` або експорт у CSV), кнопка «Відкотити». Рекомендувати також звичайний `mysqldump` таблиці.
- **БД LaunchServer недоступна.** Синхронізація виконується у черзі/в `try/catch`; правило зберігається в БД Azuriom зі статусом «не синхронізовано» (`pending`), UI показує жовтий банер «Зміни не застосовано», команда `launcherperms:reconcile` повторює. Тайм-аут з'єднання короткий. Редагування правил при цьому не блокується.
- **Звичайна робота LaunchServer при помилці БД:** якщо LaunchServer не може запитати права, `getUserByUUID` повертає null і гравець не авторизується (`AbstractSQLCoreProvider.java:85-95`, 333-347). Тому **помилкові дані в `user_permissions` (цикл ролей, зламана схема) можуть заблокувати вхід** — звідси вимога заборонити цикли й тестувати зміни на копії.
- **Як перевірити, що LaunchServer підхопив зміни:** (1) консольна команда `clients` у LaunchServer показує `Permissions` підключених (`command/service/ClientsCommand.java:54-55`) — гравець має перезайти; (2) у лаунчері після входу `launchserver.profile.<P>.show` дає видимий профіль; (3) у плагіні — кнопка «Перевірити» читає назад `user_permissions` і порівнює з очікуваним. **[К]** (щодо команди)
- **Вплив на гравців у грі:** запущену гру зміни не торкаються; зміни діють при наступному вході/оновленні сесії (п. 2.8). Відкликання прав не «викидає» гравця.
- **Секрети:** пароль БД LaunchServer зберігати через `Setting::markAsEncrypted`, права користувача БД — `SELECT, INSERT, DELETE` лише на `user_permissions` (бажано окремий акаунт). Токен експорту — довгий випадковий, порівняння `hash_equals`, rate limit (middleware `api` дає throttle, `Kernel.php`, `RouteServiceProvider.php:76-80`).

---

## 8. План реалізації й оцінка

| Крок | Зміст | Складність |
|---|---|---|
| 0 | Отримати від власника схему `user_permissions` і таблиці ролей, секцію `sql` з конфігурації LaunchServer, версію MySQL, чи БД спільна (розділ 9) | низька |
| 1 | Каркас плагіна (`plugin:create`), права, меню, переклади (uk/en) | низька |
| 2 | Міграції (`launcher_*`), налаштування з'єднання `launcher`, перевірка підключення | низька-середня |
| 3 | Каталог профілів: імпорт JSON в адмінці (варіант 3) + парсер `ClientProfile`/`updateOptional` | середня |
| 4 | `PermissionFormat` + `PermissionSync` + ledger + тести (юніт-тести форматів; інтеграційні на sqlite/mysql) | середня-висока |
| 5 | Екран «Доступи» (combobox, дерево, попередній перегляд) | середня |
| 6 | Екран «Хто що має», журнал, картка в профілі гравця | середня |
| 7 | Спостерігачі `User` (роль, `game_id`, видалення), команда `reconcile`, розклад | середня |
| 8 | Модуль LaunchServer «експорт профілів» (Java) + виклик після `syncProfilesDir` | середня |
| 9 | Резервні копії/відкат, банер «не синхронізовано», перевірка читанням | середня |
| 10 | Для справжньої безпеки модів: профіль-на-набір (організаційно) або тригер/фільтрація в `UpdateResponse` (код LaunchServer+Launcher) | висока (опційно) |

Орієнтовно: MVP (кроки 1-6 з ручним імпортом JSON) — 3-5 робочих днів досвідченого PHP/Laravel-розробника; повний обсяг із експортером і відкатом — 7-12 днів. Оцінка приблизна.

---

## 9. Відкриті питання до власника

1. Чи `user_permissions` лежить в тій самій БД, що й Azuriom? Який користувач БД? Яка точна схема (назви й типи колонок, чи є `id`, `created_at`, унікальні ключі)? Чи є таблиця ролей LaunchServer (`rolesTable`) і що в ній?
2. Фактична секція `sql` у конфігурації LaunchServer (імена `permissionsTable`, `permissionsUUIDColumn`, `permissionsPermissionColumn`, `rolesTable`...). У репозиторії її немає.
3. Яка версія MySQL/MariaDB (для `WITH RECURSIVE`)?
4. Чи потрібно, щоб плагін також **перемикав `limited`** профілю / мода (це зміна JSON на LaunchServer, не прав)? Чи достатньо, що публічність задається на стороні LaunchServer?
5. Чи потрібні моди окремо від профілів, і наскільки важливо, щоб закритий мод **не можна було завантажити в обхід** лаунчера? Якщо важливо, то профіль-на-набір модів або зміни коду (п. 2.7, варіанти A/C).
6. Режим ролей: розгортати права ролей по гравцях (рекомендовано) чи використовувати `role.<…>` у LaunchServer?
7. Як гравці потрапляють у `users.game_id`: усі реєструються на сайті (offline-UUID) чи частина входить лише через лаунчер (тоді `game_id` може бути випадковим UUID)?
8. Топологія: сайт і LaunchServer на одній машині? Чи може LaunchServer писати в БД сайту / HTTP до сайту?
9. Чи є вже ручні рядки в `user_permissions` у форматах плагіна (імпорт у ledger, чи залишити як є)?
10. Мови інтерфейсу (укр/англ), і хто має право керувати (окрема роль чи лише адміністратори).

---

## 10. Що не вдалося підтвердити

- Офіційна документація Azuriom (https://azuriom.com/en/docs/plugins та суміжні): `azuriom.com` недоступний із цього середовища (`ENOTFOUND`). Усі факти про Azuriom взято з вихідного коду GitHub. Опис міграцій «з адмінки» та поведінка на конкретній версії сайту не перевірені.
- Plugin-SkinAPI: клонується, але детально не вивчений; приклади бралися з Shop і Vote.
- Чи існує готовий плагін Azuriom для GravitLauncher: не знайдено.
- Реальна конфігурація LaunchServer (`sql`-секція, схема `user_permissions`, таблиця ролей): відсутня в репозиторії.
- Точна реакція запущеного GUI-лаунчера на зміну прав без перезапуску (коли він повторно запитує профілі/сесію).
- Додавання власного з'єднання БД у плагіні Azuriom (`config(['database.connections.…'])` у `boot()`): стандартна практика Laravel, але в Azuriom не перевірялась.
- Поведінка MySQL при циклічній рекурсії (`cte_max_recursion_depth`) та версійні вимоги `WITH RECURSIVE`: загальне знання, не перевірялося.
- Розбіжність формату UUID у `updateAuth`/`updateServerID` (дефіси) з `game_id` (без дефісів) у таблиці користувачів: виявлено в коді, на живій системі не перевірялось.
- Чи працює клієнтський механізм прав на моди в конкретній збірці лаунчера (потрібен запуск `LauncherRuntime-4.0.6` на стенді).

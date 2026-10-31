# telegram-guides

Android-библиотека интерактивных гайдов поверх нативного интерфейса Telegram,
пакет `ru.n08i40k.guides`. Сценарий проигрывается в превью внутри окна гайда:
настоящие `MainTabsActivity`, `ChatActivity`, `ProfileActivity` и шиты хоста,
но с локальными данными. Ничего не пишется в базу, историю и контроллеры аккаунта;
из сети загружаются только профили и графика.

Библиотека не зависит от Streaks. Классы Telegram берутся из обработанного
`Telegram.jar` (задача `processTelegramJar` корневого проекта) как `compileOnly`.

```sh
./gradlew :telegram-guides:assembleDebug   # только библиотека
./gradlew :buildDexDebug                   # плагин вместе с библиотекой
```

## Пакеты

- `api/` — контракты хоста: `GuidePresentation`, бейджи, команды, содержимое сообщений, логгер.
- `model/` — сценарий, шаги, действия, чаты, профили, сообщения и DSL.
- `player/` — проигрывание шагов и стек сцен с переходами.
- `scene/` — базовые сцены; `chat/`, `list/`, `profile/` — встроенные экраны Telegram.
- `catalog/` — окно каталога и хранилище прогресса.
- `profile/` — загрузка настоящих профилей.
- `ui/` — окно гайда, управление окнами, подписи.
- `util/` — координаты, масштабирование, ожидание раскладки, доступ к приватным полям Telegram.
- `demo/` — технический сценарий `ChatListGuideScript`.

## Запуск

```kotlin
GuideDialog(activity, script).show()

val guides = GuideUiManager(
    logger = logger,
    catalogFactory = { catalog },
    presentation = presentation,
    labels = { labels },
    debug = { debugMode },
    progressFactory = { GuideProgress(preferences, "guides.completed") },
)
guides.start()    // UI thread
guides.show()     // кнопка в настройках
guides.refresh()  // LaunchActivity.onResume
guides.pause()    // LaunchActivity.onPause
guides.destroy()  // выгрузка хоста
```

`GuideUiManager.show()` открывает каталог над `LaunchActivity`, если задан
`catalogFactory`, или один сценарий из `scriptFactory`. Вызовите его из настроек хоста.

Первый шаг запускается при открытии окна и сначала загружает профили. Каждое
нажатие «Далее» выполняет следующий шаг; действия шага идут по очереди, состояние
сцен сохраняется между шагами. «Готово» на последнем шаге закрывает окно и
засчитывает прохождение. Закрытие окна отменяет ожидающие действия; при ошибке
окно закрывается и показывает сообщение, подробности уходят в логгер.

## Сценарии

```kotlin
val anna = GuideChat(1, "Анна", "До встречи!", profile = GuideProfile.Username("durov"))

val script = guideScript("Список чатов") {
    step("Знакомый список", "Здесь показаны локальные диалоги.") {
        +ShowChats(listOf(anna))
    }
    step("Новое сообщение", "Обновляем демонстрационный чат.") {
        +TapChat(anna.id)
        +Wait(300.milliseconds)
        +UpdateChat(anna.copy(message = "Привет!", unreadCount = 1))
    }
}
```

Повторяющиеся последовательности выносятся в расширения `GuideStepBuilder`,
общие шаги — в расширения `GuideScriptBuilder`. `@GuideDsl` не даёт объявить шаг
внутри шага.

Конструктор `GuideScript` проигрывает состояние сцен всухую и отклоняет сценарий,
который упадёт при показе: неизвестные ID, действия списка внутри чата, действия
чата вне его, лишний `Back`, дубликаты и нарушенный порядок сообщений.

### Список чатов

`GuideChat.id` — локальный ключ сценария, не ID Telegram. `ShowChats` заменяет
список и сбрасывает подсветку и прокрутку; из чата он возвращает к новому списку.
`UpdateChat` заменяет данные строки, не двигая её. `HighlightChat(null)` снимает
подсветку, `ScrollToChat` без анимации поднимает строку к верхнему краю.

Строка с `profile` берёт из Telegram имя, аватар, статус и verified; превью,
время, счётчики, закрепление и mute задаёт сценарий. Без `profile` строка
рисуется с локальным именем и буквенным аватаром.

### Профили

Профили всех `ShowChats` и `UpdateChat` загружаются до первой сцены.
`GuideProfile.Username` выполняет `contacts.resolveUsername` и подходит для
пользователей, ботов, групп и каналов. `GuideProfile.UserId` — только для
пользователя, уже известного аккаунту с `access_hash`. Повторные username
объединяются без учёта регистра и `@`. На запрос даётся 20 секунд.

`GuideProfiles` привязан к аккаунту; все сцены одного окна используют общий
экземпляр. Его можно прогреть заранее через `prefetch` и передать в `GuideDialog`.

### Чат

`OpenChat(id, messages)` выдвигает справа встроенный `ChatActivity` с профилем
строки; у строки обязан быть `profile`. `ReturnToChats` уводит чат вправо и
возвращает сохранённый список. История целиком локальная: Telegram рисует фон,
пузырьки, даты и поле ввода, но загрузка истории, события аккаунта, отметки
прочтения и черновики отключены.

`GuideMessage` — положительный уникальный ID, текст, направление и дата в Unix
секундах; история передаётся от старых к новым. `ShowMessages` заменяет историю
без анимации. `SendMessage`, `ReceiveMessage` и `AppendMessage` добавляют
сообщение через `ChatActivity.processNewMessages` и ждут нативный аниматор.

`TapChat`, `TapAt`, `TapTarget`, `TapMessage` только показывают касание и не
вызывают обработчики Telegram: результат задаётся следующим действием.
Цели чата перечислены в `GuideChatTargets`. `OpenChatMenu` открывает настоящее
меню ⋮ этого чата со штатной анимацией попапа, `OpenPluginsMenu` листает его на
страницу Plugins с настоящими пунктами плагинов, `TapMenuItem(text)` касается
видимого пункта по тексту. `OpenPanel` закрывает меню так же, как закрывается попап.

Часть состояния `ChatActivity` приватна; весь доступ к нему собран в
`GuideChatHistory`. При обновлении Telegram имена полей нужно сверять с хостом.

## Расширение

Возможности хоста подключаются через `GuidePresentation`:

- `panel(context, key, peer, account)` создаёт сцену для `OpenPanel(key)` поверх
  текущего чата; `Back` закрывает её.
- `message(account, source, content)` превращает `GuideMessage.content` в
  `MessageObject`, например в сервисное сообщение с кнопкой.
- `openBadges(root, account)` открывает `GuideBadgeSession` на время показа.
  `GuideBadge` — непрозрачный тип хоста; плеер передаёт в `update` бейджи по
  настоящим ID профилей, а `SetBadge(id, badge)` меняет бейдж строки.
- `chat(arguments)` позволяет подставить наследника `GuideChatActivity`.

Готовые сцены для панелей:

- `GuideFragmentScene` — любой `BaseFragment` целой страницей: `target`,
  `targetPoint`, `scrollTarget`, `command`, `refreshTheme`, `cleanup`.
- `GuideOverlayScene` — модальное содержимое поверх предыдущей сцены с
  анимациями Telegram: `GuideLayer.SHEET` выезжает как BottomSheet,
  `GuideLayer.DIALOG` появляется как диалог.
- `ProfileActivityGuideScene` — наследник `GuideProfileActivity` с целью `BADGE`.

Все они наследуют `GuideViewportScene`: раскладка на ширине устройства с
масштабированием в превью, блокировка ввода и accessibility, слой касаний.
Свою сцену можно собрать на нём же или реализовать `GuideScene` целиком.

`ScrollTarget(key)` ждёт, пока сцена докрутит до цели; ставьте его отдельным
шагом перед `TapTarget(key)`. `Extension(command)` доставляет текущей сцене
команду хоста — наследника `GuideExtension`:

```kotlin
sealed interface DemoCommand : GuideExtension {
    data class Indexed(val days: Int) : DemoCommand
}

GuideOverlayScene(
    context,
    GuideLayer.SHEET,
    content = { sheetContent },
    command = { (it as DemoCommand.Indexed).days.let(::showProgress) },
)
```

`BottomSheet.takeEmbeddedContent()` строит шит без окна и отдаёт его нативный
контейнер с фоном, тенью и отступами — готовое содержимое для `GuideLayer.SHEET`.

Сцены по возможности повторяют настоящий путь пользователя: те же экраны,
те же пункты меню, те же переходы. Собственная разметка вместо нативной —
только там, где Telegram показывает экран в отдельном окне.

## Каталог

`GuideCatalogEntry(id, title, description, create)` — запись каталога; `id`
хранится как ключ прохождения в `GuideProgress`. Записи открываются по порядку,
пройденные можно повторять, `debug` открывает все. `GuideLabels` содержит все
строки интерфейса библиотеки; хост передаёт их из своей локализации.

Если передать в `GuideUiManager` параметр `link`, долгое нажатие на карточку
каталога открывает нативное меню Telegram с пунктом копирования ссылки на урок. Формат ссылок и их перехват задаёт
хост; открытую ссылку он передаёт в `GuideUiManager.show(entryId)` — откроется
каталог и поверх него урок, независимо от блокировки. Streaks использует
`tg://streaks/guides` для каталога и `tg://streaks/guides/<id>` для урока и
перехватывает их в `Browser.openUrl`.

## Streaks

Уроки Streaks лежат в `streaks/guide/lessons`: каталог и подписи — в
`StreakBasicsGuides`, общие участники и переходы — в `StreakGuideFixtures`.
`streaks/guide/presentation` строит панели из настоящих экранов плагина
(`StreakControlFragment`, `RebuildBottomSheet`, `StreakInfoBottomSheet`, попап
стрика) с локальным состоянием, `streaks/guide/badge` рисует огоньки сценария.
Настройка `DEBUG_MODE` передаётся из Python в `Plugin.setGuideDebugMode`.

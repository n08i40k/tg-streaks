# reflection

Доступ к скрытым и версионно-зависимым членам классов клиента через `MethodHandle`.

Отдельная сборка (`includeBuild("reflection")`) из двух частей:

- корневой проект — библиотека `ru.n08i40k:reflection`: `getFieldGetter`, `getMethodHandle`,
  `getConstructorHandle`, их `IfExists`-варианты, `retype`, `getAccessibleFields`, аннотации
  `@RequiresClient*` и аннотации описаний (пакет `ru.n08i40k.reflection.mirror`);
- `processor/` — KSP-процессор `ru.n08i40k:reflection-processor`: по интерфейсам-описаниям
  генерирует
  `Accessors.kt` и сверяет описания с jar клиента.

## Подключение

```kotlin
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.reflection)
    ksp(libs.reflection.processor)
}
```

Опции KSP:

| Опция                      | Значение                                                                                                                          |
|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `reflection.classpath`     | jar клиента для проверки, через `File.pathSeparator`. Корневой `build.gradle.kts` передаёт `libs/Telegram.jar` всем модулям с KSP |
| `reflection.clientVersion` | версия этих jar; по умолчанию читается из `BuildVars.BUILD_VERSION_STRING`                                                        |
| `reflection.optIn`         | `true` — помечать версионные accessors `@RequiresClientSince/Before/Between`                                                      |

## Описания

Интерфейс с `@Mirror(Class::class)` или `@RuntimeMirror("binary.Name")` описывает члены одного
класса.
Все описания пакета превращаются в один `Accessors.kt` этого пакета. Видимость сгенерированных
свойств —
`public` для публичного интерфейса, иначе `internal`.

```kotlin
@Mirror(ChatActivity::class)
interface ChatActivityMirror {
    @Getter(retype = [RecyclerView::class, ChatActivity::class])
    val chatListView: Any?                 // ChatActivity$chatListView

    @Getter
    @Setter
    val paused: Any?                       // ChatActivity$paused, ChatActivity$paused$$setter

    fun processNewMessages(messages: ArrayList<*>)   // ChatActivity$processNewMessages
}

/** MonthView приватный, поэтому ищется по имени */
@RuntimeMirror($$"org.telegram.ui.CalendarActivity$MonthView")   // CalendarActivity$MonthView: Class<*>
interface MonthViewMirror {
    @Getter(retype = [Int::class, View::class])
    val currentYear: Any?
}

@Mirror(TextSetting::class)
interface TextSettingMirror {
    @New
    @Since("12.5.1")                       // TextSetting$$init$$12_5_1: MethodHandle?
    fun create(text: String, subtext: String, icon: String)

    @New
    @Since("12.1.1")
    @Before("12.5.1")                      // TextSetting$$init$$12_1_1: MethodHandle?
    fun create(text: String, icon: String)
}
```

| Описание            | Свойство             | Функция                              |
|---------------------|----------------------|--------------------------------------|
| `@Getter val name`  | `Owner$name`         | `getFieldGetter`                     |
| `@Setter val name`  | `Owner$name$$setter` | `getFieldSetter`                     |
| `fun name(...)`     | `Owner$name`         | `getMethodHandle`                    |
| `@New fun any(...)` | `Owner$$init`        | `getConstructorHandle`               |
| `@Fields val any`   | `Owner$$fields`      | `getAccessibleFields`                |
| `@RuntimeMirror`    | `Owner`              | `Class.forName` / `getClassIfExists` |

`Owner` — бинарное имя класса без пакета (`CalendarActivity$MonthView`). Тип свойства в описании и
тип
возврата функции не используются.

- Типы параметров — обычные Kotlin-типы: `Int` — `int`, `Int?` — `java.lang.Integer`, `String`,
  `ArrayList<*>`,
  `IntArray`, `Array<T>`. Параметр класса из `@RuntimeMirror` задаётся как
  `@Type("binary.Name") name: Any?`.
- `@Retype(R::class, P::class, ...)` на функции и `retype = [R::class, P::class, ...]` в `@Getter`/
  `@Setter` —
  `MethodHandle.asType`: сначала возвращаемый тип, затем параметры; `Int::class` — `int`,
  `Unit::class` —
  `void`, `Any::class` — `Object`.
- `@Name("jvmName")` — имя члена, если оно отличается от имени в описании.
- KDoc описания и имена параметров переносятся в сгенерированный код.

Ссылка `Class::class` в сгенерированном коде загружает класс при инициализации файла, поэтому
классы,
которых нет хотя бы в одном поддерживаемом клиенте, описываются через `@RuntimeMirror`.

## Версии и проверка

`@Since("a")` — член есть начиная с клиента `a`, `@Before("b")` — его нет начиная с `b`,
`@Optional` — может
отсутствовать независимо от версии (есть не во всех форках). С любой из этих аннотаций свойство
nullable
и строится через `IfExists`; `@Since` добавляет к имени суффикс `$$a_b_c`. Члены класса из
`@RuntimeMirror` с такими аннотациями и члены с такими `@Type`-параметрами тоже nullable.

При сборке процессор открывает `reflection.classpath` и для версии клиента `V` проверяет каждый член
(поля, методы и конструкторы — объявленные в самом классе, с точной сигнатурой параметров):

| Диапазон                               | Член отсутствует в jar                  | Член есть в jar |
|----------------------------------------|-----------------------------------------|-----------------|
| `V` в `[since, before)` или границ нет | ошибка (у `@Optional` — предупреждение) | —               |
| `V >= before`                          | —                                       | предупреждение  |
| `V < since`                            | не проверяется                          | не проверяется  |

Ошибка о пропавшем методе перечисляет его перегрузки в `V`. Когда после обновления `Telegram.jar`
член пропадает, ему ставится `@Before` с версией, где он исчез (и при необходимости добавляется
вариант
с новой сигнатурой и `@Since`), либо описание удаляется.

Accessors, которые строятся из значений других accessors (тип поля, вызов handle, выбор из
нескольких
классов), пишутся вручную в обычном Kotlin-файле того же пакета.

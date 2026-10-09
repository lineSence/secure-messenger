# Гайдлайны UX/UI для мобильного клиента (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-09.

| Факт | Источник · дата |
|---|---|
| Telegram для Android — крупнейший редизайн: новая нижняя панель для перехода между чатами, настройками, профилем в одно касание | [Telegram blog](https://telegram.org/blog/crafting-android-design-and-more) · 2026-02-09 |
| Material 3: навигационная панель — для окон уже 600 dp (телефон в портрете), 3–5 равнозначных пунктов | [M3 Navigation bar](https://m3.material.io/components/navigation-bar/overview) · на 2026-10-09 |
| Material 3 Expressive больше не рекомендует navigation drawer (боковое меню) | [M3 Navigation drawer](https://m3.material.io/components/navigation-drawer/overview) · на 2026-10-09 |
| Цель касания — не меньше 48×48 dp (≈ 9 мм); значок может быть 24 dp, остальное — отступы | [M3 Structure](https://m3.material.io/foundations/designing/structure) · на 2026-10-09; [Android Accessibility Help](https://support.google.com/accessibility/android/answer/7101858?hl=en) · на 2026-10-09 |
| WCAG 2.2: контраст текста ≥ 4.5:1 (крупного — 3:1), уровень AA; минимальная цель 24×24 CSS px (2.5.8) | [WCAG 2.2](https://www.w3.org/TR/WCAG22/) · на 2026-10-09 |
| Значки и элементы управления — контраст ≥ 3:1 (1.4.11 Non-text Contrast) | [W3C Understanding 1.4.3](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) · на 2026-10-09 |
| Тёмная тема: избегать насыщенных цветов — на тёмном фоне они не дают контраст 4.5:1 | [Material Dark theme](https://m2.material.io/design/color/dark-theme.html) · на 2026-10-09; [Android: dark theme](https://developer.android.com/develop/ui/views/theming/darktheme) · обновлено 2026-10-01 |
| Скрытая навигация (меню-«гамбургер») почти вдвое снижает обнаруживаемость, задачи на телефоне — на 15% медленнее | [NN/g](https://www.nngroup.com/articles/hamburger-menus/) · 2016-06-26 |
| 10 эвристик Нильсена; №1 — «видимость состояния системы»: всегда сообщать, что происходит, с быстрой обратной связью | [NN/g](https://www.nngroup.com/articles/ten-usability-heuristics/) · 1994, пересмотрено 2024-01-30 |
| Пустые состояния — повод сообщить состояние системы и подсказать следующее действие | [NN/g](https://www.nngroup.com/articles/empty-state-interface-design/) · на 2026-10-09 |
| Material 3 motion: emphasized decelerate — cubic-bezier(0.05, 0.7, 0.1, 1.0); standard — cubic-bezier(0.2, 0, 0, 1); длительности: short 50–200 мс, medium 250–400 мс, long 450–600 мс | [M3 Easing and duration](https://m3.material.io/styles/motion/easing-and-duration/tokens-specs) · на 2026-10-09 |
| WCAG 2.2, 2.3.3 «Анимация от взаимодействий» (AAA): анимацию можно отключить; достаточный способ — учитывать `prefers-reduced-motion` | [W3C Understanding 2.3.3](https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html) · на 2026-10-09 |
| В Android настройка «Убрать анимацию» в специальных возможностях; Compose и Lottie учитывают её сами, свои анимации нужно проверять | [eevis.codes](https://eevis.codes/blog/2022-12-12/android-animations-and-reduced-motion/) · 2022-12-12 |
| Telegram: реакции ставятся из меню сообщения; быстрые ответы — свайпом сообщения | [Android Central](https://www.androidcentral.com/how-apply-reactions-telegram-messages) · на 2026-10-09 |
| Material Symbols — под лицензией Apache 2.0 | [google/material-design-icons](https://github.com/google/material-design-icons) · на 2026-10-09 |

## Android: уведомления и работа в фоне (для UX-17…UX-19)
| Факт | Источник |
|---|---|
| С Android 13 (API 33) показ уведомлений требует разрешения `POST_NOTIFICATIONS`, которое пользователь даёт в системном окне | [Android Developers: Behavior changes, all apps (Android 13)](https://developer.android.com/about/versions/13/behavior-changes-all), на 2026-10-09 |
| Мессенджер, который не может пользоваться FCM, вправе просить исключение из Doze через `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — системное окно прямо из приложения; пользователь также может включить исключение сам: Настройки → Батарея | [Android Developers: Optimize for Doze and App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby), страница обновлена 2026-08-18 |
| С Android 14 (API 34) у каждой foreground service нужно объявить тип; тип `remoteMessaging` описан как «передача текстовых сообщений с одного устройства на другое» | [Android Developers: Foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types), на 2026-10-09 |
| Уведомление на экране блокировки имеет один из трёх уровней видимости: public (полностью), private (только базовая информация: приложение и значок), secret (скрыто) | [Android Developers: Notifications (Mobile design)](https://developer.android.com/design/ui/mobile/guides/home-screen/notifications), на 2026-10-09 |
| Material Design: на первом запуске просить только критичные и очевидные разрешения, остальные — в момент использования функции | [Material Design 2: Android permissions](https://m2.material.io/design/platform-guidance/android-permissions.html), на 2026-10-09 |

## Выводы (наши, не факты)
- Основная навигация — видимая нижняя панель (А); боковое меню (Б) сохраняем только как сравнение по просьбе владельца.
- Полоса состояния связи — всегда на экране (эвристика №1; решение владельца 2026-10-09).
- Все значки — свои SVG по сетке 24 dp, кнопки — 48 dp.
- Разрешения «Уведомления» и «Работа в фоне» — критичные для мессенджера без FCM (ADR-10), поэтому спрашиваем их на первом запуске, с объяснением до системного окна; можно пропустить.
- Тип foreground service выбрать на шаге 3 плана MVP, перепроверив факты по [DOCS-RECHECK-BEFORE-USE].

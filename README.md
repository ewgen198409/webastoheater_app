# Webasto Heater - Android приложение для управления нагревателем сухой фен "Webasto". Для проекта ESP8266_webasto.

<div align="center">

  [![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
  [![Android](https://img.shields.io/badge/Android-5.0%2B-brightgreen.svg)](https://android.com)
  [![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

## 📖 О проекте

Webasto Heater - это современное Android приложение, разработанное на Kotlin, которое является мобильным клиентом для проекта [ESP8266_webasto](https://github.com/your-username/ESP8266_webasto). Приложение позволяет удаленно управлять нагревателями Webasto через Bluetooth соединение, предоставляя полный контроль над работой нагревателя, мониторинг ключевых параметров и настройку рабочих характеристик.

## ✨ Возможности

### 🔥 Управление нагревателем
- **Включение/выключение** нагрева одним касанием
- **Регулировка режимов** работы (UP/DOWN)
- **Прокачка топливной** системы
- **Сброс ошибок** и диагностика
- **Реальный мониторинг** состояния системы

### ⚙️ Настройки параметров
- **Размер топливного насоса** (10-100 мл/1000 импульсов)
- **Температурные настройки**:
  - Целевая температура (150-250°C)
  - Минимальная температура (140-240°C)
  - Температура перегрева (200-300°C)
  - Температура предупреждения (180-280°C)
- **Настройки вентилятора** и свечи накаливания
- **Поддержка темной темы**

### ⛽ Мониторинг расхода топлива
- **Отслеживание общего расхода** топлива
- **Текущий расход** в литрах в час
- **Параметры работы насоса** в реальном времени
- **Сброс счетчика** расхода

## 📸 Скриншоты

<div align="center">

| Мониторинг топлива | Управление нагревателем | Настройки параметров |
|-------------------|------------------------|---------------------|
| <img src="https://github.com/user-attachments/assets/d0a5095c-ee3d-457c-a50d-ffc9e181c97f" width="250" alt="Мониторинг топлива"> | <img src="https://github.com/user-attachments/assets/7a6b24cf-4d3b-4f42-a2ad-66f9afd11376" width="250" alt="Управление нагревателем"> | <img src="https://github.com/user-attachments/assets/ed268191-dfd1-4bca-9c81-ddcf5f53af63" width="250" alt="Настройки параметров"> |

</div>

## 🛠 Технический стек

- **Язык**: Kotlin
- **Минимальная версия Android**: 5.0 (API 21)
- **Архитектура**: MVVM + Fragments
- **UI**: Material Design, ViewPager2
- **Связь**: Bluetooth SPP (Serial Port Profile)
- **Сборка**: Gradle

## 🔗 Связанные проекты

Это приложение работает в паре с проектом [ESP8266_webasto](https://github.com/your-username/ESP8266_webasto) - firmware для ESP8266, который обеспечивает:

- Bluetooth интерфейс для управления Webasto
- Обработку команд от мобильного приложения
- Мониторинг состояния нагревателя
- Преобразование протоколов связи

## 📦 Установка

### Требования
- Android 5.0 (API 21) или выше
- Устройство с поддержкой Bluetooth
- Устройство Webasto с прошивкой ESP8266_webasto

### Разрешения
```xml
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
```

## 📄 Лицензия
- Этот проект распространяется под лицензией MIT. Подробнее см. в файле LICENSE.

## 👥 Авторы
- ewgeniy1984 vs GithubCopilot

## ⭐ Поддержка
- Если вам понравился проект, поставьте звезду ⭐ на GitHub!

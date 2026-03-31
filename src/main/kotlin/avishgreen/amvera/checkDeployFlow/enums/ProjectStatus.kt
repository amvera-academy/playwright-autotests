package avishgreen.amvera.checkDeployFlow.enums

enum class ProjectStatus(val text: String) {
    BUILDING("Выполняется сборка"),
    RUNNING("Выполняется запуск"),
    SUCCESS("Приложение запущено"),
    STOPPED("Приложение остановлено"),
    DEPLOYING("Проект развертывается"),
    ERRORINCONFIG("Ошибка конфигурации. Смотри лог сборки/приложения"),
    ERROR("Ошибка сборки"),
    DEPLOING("Проект развертывается"),
    NOTDEPLOYED("Проект не развертывался");

    companion object {
        // Метод для поиска Enum по тексту со страницы
        fun fromText(text: String?): ProjectStatus? {
            return entries.find { it.text == text?.trim() }
        }
    }
}
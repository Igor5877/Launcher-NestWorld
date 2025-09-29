using Prestarter.Downloaders;

namespace Prestarter
{
    /// <summary>
    ///     Класс конфигурации приложения
    /// </summary>
    internal class Config
    {
        /// <summary>
        ///     Название проекта, как указано в настройках лаунчсервера
        /// </summary>
        public static readonly string Project = "Beta-NestWorld";

        /// <summary>
        ///     Версия приложения
        /// </summary>
        public static readonly string Version = "2.0.0";

        /// <summary>
        ///     URL для скачивания лаунчера. Если null - использовать встроенный в модуль
        /// </summary>
        public static readonly string LauncherDownloadUrl = null;

        /// <summary>
        ///     Показывать ли диалог перед скачиванием Java
        /// </summary>
        public static readonly bool DownloadQuestionEnabled = true;

        /// <summary>
        ///     Использовать общую Java для всех лаунчеров
        /// </summary>
        public static readonly bool UseGlobalJava = true;

        /// <summary>
        ///     Загрузчик Java, использует Adoptium и OpenJFX
        /// </summary>
        public static readonly IRuntimeDownloader JavaDownloader =
            new CompositeDownloader(new AdoptiumJavaDownloader(), new OpenJFXDownloader(true));
        
        /// <summary>
        ///     Наименование диалога
        /// </summary>
        public static string DialogName => $"{Project}";
        
        /// <summary>
        /// Основной цвет
        /// </summary>
        public static readonly string PrimaryColorHex = "#720606";
        /// <summary>
        /// Цвето фона
        /// </summary>
        public static readonly string BackgroundColorHex = "#00417e";
        /// <summary>
        /// Цвет текста
        /// </summary>
        public static readonly string ForegroundColorHex = "#969696";
        /// <summary>
        /// Цвет Кнопки
        /// </summary>
        public static readonly string ButtonColorHex = "#2afa00";
        /// <summary>
        /// Цвет Кнопки при наведении
        /// </summary>
        public static readonly string ButtonHoverColorHex = "#2E2E2E";
    }
}

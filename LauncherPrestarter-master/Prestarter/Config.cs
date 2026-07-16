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
        /// Основной цвет (акцент, заполнение прогресс-бара)
        /// </summary>
        public static readonly string PrimaryColorHex = "#9F0200";
        /// <summary>
        /// Цвет фона окна
        /// </summary>
        public static readonly string BackgroundColorHex = "#111114";
        /// <summary>
        /// Цвет второстепенного текста (статус загрузки)
        /// </summary>
        public static readonly string ForegroundColorHex = "#8888A0";
        /// <summary>
        /// Цвет заголовка (название проекта)
        /// </summary>
        public static readonly string TitleColorHex = "#E4E4F0";
        /// <summary>
        /// Цвет рамки кнопки закрытия в обычном состоянии (rgba(255,255,255,0.22))
        /// </summary>
        public static readonly string ButtonColorHex = "#38FFFFFF";
        /// <summary>
        /// Цвет кнопки закрытия при наведении
        /// </summary>
        public static readonly string ButtonHoverColorHex = "#9F0200";
    }
}

using System;
using System.IO;
using System.IO.Compression;
using System.Linq;

namespace Prestarter.Helpers
{
    internal abstract class DownloaderHelper
    {
        public static void UnpackZip(string zipPath, string targetPath, bool skipFirstDirectory)
        {
            // Entries are written only below this directory ("Zip Slip": names like ..\..\x or C:\x must not escape)
            var fullTargetPath = Path.GetFullPath(targetPath);
            if (!fullTargetPath.EndsWith(Path.DirectorySeparatorChar.ToString()))
                fullTargetPath += Path.DirectorySeparatorChar;

            using (var archive = ZipFile.OpenRead(zipPath))
            {
                string rootFolder = null;
                if (skipFirstDirectory)
                {
                    var firstEntry = archive.Entries.FirstOrDefault();
                    if (firstEntry != null)
                    {
                        rootFolder = firstEntry.FullName.Split('/')[0] + "/";
                    }
                }

                foreach (var entry in archive.Entries)
                {
                    string fileName;
                    if (skipFirstDirectory && !string.IsNullOrEmpty(rootFolder) && entry.FullName.StartsWith(rootFolder))
                    {
                        fileName = entry.FullName.Substring(rootFolder.Length);
                    }
                    else
                    {
                        fileName = entry.FullName;
                    }

                    fileName = fileName.Replace('/', '\\');
                    if (string.IsNullOrEmpty(fileName)) continue;
                    var path = Path.GetFullPath(Path.Combine(targetPath, fileName));
                    if (!path.StartsWith(fullTargetPath, StringComparison.OrdinalIgnoreCase))
                        throw new InvalidDataException("Unsafe path in archive: " + entry.FullName);
                    if (entry.FullName.EndsWith("/"))
                        Directory.CreateDirectory(path);
                    else
                    {
                        Directory.CreateDirectory(Path.GetDirectoryName(path));
                        entry.ExtractToFile(path, true);
                    }
                }
            }
        }
    }
}
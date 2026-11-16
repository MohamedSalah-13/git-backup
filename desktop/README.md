# Git Backup Desktop (نسخة JavaFX)

برنامج سطح مكتب يقوم بما تقوم به سكريبتات PowerShell في جذر المستودع، لكن دون حاجة إلى تثبيت Git:
يعرض مستودعات حسابك على GitHub، تختار منها، ثم ينسخ المختار إلى ملفات ZIP (ملفات الفرع الرئيسي + التاريخ الكامل).

هذه نسخة أولى (0.1) للتجربة والتطوير، وليست جاهزة للتوزيع على العملاء بعد.

## المتطلبات

- JDK 21 أو أحدث
- Maven 3.9 أو أحدث

## التشغيل

```powershell
mvn package
java -jar target\git-backup-desktop.jar
```

أو أثناء التطوير:

```powershell
mvn javafx:run
```

النسخ بدون نافذة (للجدولة لاحقًا)، وينسخ الاختيار المحفوظ:

```powershell
java -jar target\git-backup-desktop.jar --backup
java -jar target\git-backup-desktop.jar --backup --force
```

رمز الخروج: 0 نجاح، 1 فشل بعض المستودعات، 2 تعذّر البدء.

## تسجيل الدخول

- إن كان GitHub CLI مثبتًا ومسجّل الدخول (`gh auth login`) يستخدمه البرنامج تلقائيًا.
- أو من زر "تسجيل الدخول": الصق رمز وصول شخصي (Personal Access Token) بصلاحية `repo`.
  يُحفظ مشفّرًا بـ Windows DPAPI، فلا يقرؤه إلا نفس مستخدم Windows على نفس الجهاز.

## التوافق مع نسخة PowerShell

النسختان تكتبان نفس `backup-state.json` ونفس أسماء ملفات ZIP وبنفس حساب "البصمة"،
فإن وجّهت البرنامج إلى نفس مجلد الحفظ يكمل من حيث توقفت السكريبتات دون إعادة نسخ.

لا تشغّل النسختين على نفس مجلد الحفظ في الوقت نفسه.

## أين يحفظ البرنامج ملفاته

`%LOCALAPPDATA%\GitBackupDesktop`: الإعدادات (`config.json`)، الرمز المشفّر، نسخ محلية للمستودعات (`mirrors`) لتسريع التحديثات، والسجلات (`logs`).
يمكن تغيير المكان بمتغير البيئة `GITBACKUP_HOME`.

## بنية الكود

| الحزمة | المحتوى |
|---|---|
| `com.gitbackup.backup` | محرك النسخ (JGit) وطبقة الخدمة |
| `com.gitbackup.github` | الاتصال بـ GitHub API وتخزين الرمز |
| `com.gitbackup.store` | قراءة وكتابة الإعدادات وملف الحالة |
| `com.gitbackup.ui` | نافذة JavaFX (عربي / إنجليزي) |

## ما لم يُنفَّذ بعد

- الاستعادة من داخل البرنامج (حاليًا يدويًا حسب `RESTORE.txt` داخل كل ZIP).
- تشفير ملفات ZIP.
- مثبّت (installer) وأيقونة بجوار الساعة وجدولة داخلية.
- تسجيل الدخول عبر المتصفح (OAuth) بدل الرمز.
- المنظمات، و GitLab / Bitbucket.
- Issues و Pull Requests و Wiki و Releases و Git LFS.
- نظام التراخيص.

## للمطوّر

`-Dgitbackup.screenshot=file.png` يحفظ صورة للنافذة بعد تحميل القائمة ثم يخرج، ومع `-Dgitbackup.autorun=true` ينفّذ النسخ أولًا. يُستخدمان للفحص الآلي فقط.

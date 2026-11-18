# نسخ مستودعات GitHub احتياطيًا إلى ZIP

[![CI](https://github.com/MohamedSalah-13/git-backup/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/MohamedSalah-13/git-backup/actions/workflows/ci.yml)
[![Java 25](https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white)](desktop/pom.xml)
[![JavaFX 27](https://img.shields.io/badge/JavaFX-27-2C5D8F)](desktop/README.md)
[![PowerShell 5.1](https://img.shields.io/badge/PowerShell-5.1-5391FE?logo=powershell&logoColor=white)](backup-repos.ps1)
[![Platform: Windows](https://img.shields.io/badge/platform-Windows-0078D6?logo=windows&logoColor=white)](#المتطلبات)
[![License: All rights reserved](https://img.shields.io/badge/license-All%20rights%20reserved-lightgrey)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/MohamedSalah-13/git-backup)](https://github.com/MohamedSalah-13/git-backup/commits/main)

ينسخ مستودعات حسابك على GitHub إلى مجلد تختاره (Google Drive أو أي قرص)، كل مستودع في ملف ZIP مستقل. المستودع الذي لم يتغير منذ آخر تشغيل يُتخطى.

## المتطلبات

- Git for Windows
- GitHub CLI مع تسجيل الدخول: `gh auth login`

## الملفات

| الملف | الوظيفة |
|---|---|
| `backup-gui.cmd` | يفتح نافذة اختيار المستودعات (نقرة مزدوجة) |
| `backup-gui.ps1` | كود النافذة |
| `backup-repos.ps1` | السكريبت الأساسي الذي ينفّذ النسخ |
| `config.json` | الإعدادات |
| `run-backup.cmd` | تشغيل النسخ مباشرة بدون نافذة |
| `install-schedule.ps1` | إنشاء أو حذف المهمة المجدولة اليومية |

## النافذة

افتح `backup-gui.cmd`:

1. اضغط **استعراض** واختر مجلد الحفظ (مثل `G:\My Drive\GitBackups`).
2. ضع علامة بجوار المستودعات التي تريد نسخها.
3. اضغط **نسخ المحدد الآن**. التفاصيل تظهر في أسفل النافذة.

- **حفظ الاختيار** يحفظ العلامات دون نسخ. الاختيار المحفوظ هو ما ينسخه التشغيل المجدول أيضًا.
- إن حدّدت كل المستودعات فالمستودعات التي تنشئها لاحقًا تُنسخ تلقائيًا. إن حدّدت بعضها فقط فالجديد لا يُنسخ حتى تحدده.
- عمود **الحالة** تقدير سريع (يقارن تاريخ آخر تعديل بتاريخ آخر نسخة). عند النسخ الفعلي يُفحص كل مستودع بدقة.

## الإعدادات (`config.json`)

| الخانة | المعنى |
|---|---|
| `destination` | مجلد حفظ النسخ (الشرطة المائلة تُكتب مرتين: `G:\\My Drive\\GitBackups`) |
| `keepVersions` | عدد النسخ المحفوظة لكل مستودع (الافتراضي 3) |
| `include` | المستودعات المختارة. فارغة = كل المستودعات. تملؤها النافذة |
| `owners` | فارغة = حسابك. أضف أسماء منظمات أو حسابات أخرى عند الحاجة |
| `exclude` | مستودعات لا تظهر ولا تُنسخ أبدًا، وتقبل `*` مثل `test-*` |
| `includeForks` | إظهار ونسخ الـ forks أيضًا |
| `includeArchived` | إظهار ونسخ المستودعات المؤرشفة |
| `workDir` | مجلد العمل المحلي (نسخ مؤقتة وسجلات). فارغ = مجلد `work` بجانب السكريبت |
| `keepMirrors` | الاحتفاظ بنسخة محلية لتسريع التحديثات التالية |

## التشغيل بدون نافذة

```powershell
.\backup-repos.ps1                      # نسخ ما تغيّر من المستودعات المختارة
.\backup-repos.ps1 -DryRun              # عرض ما سيُنسخ دون تنفيذ
.\backup-repos.ps1 -Only sunbula        # مستودع محدد هذه المرة (يقبل * وأكثر من اسم بفواصل)
.\backup-repos.ps1 -Force               # إعادة النسخ حتى لو لم يتغير شيء
.\backup-repos.ps1 -Destination H:\Backups   # وجهة مختلفة لهذه المرة
```

## التحديث التلقائي

```powershell
.\install-schedule.ps1 -At 21:00    # تشغيل يومي الساعة 9 مساءً
.\install-schedule.ps1 -Remove      # إلغاء الجدولة
```

المهمة تعمل وأنت مسجّل الدخول إلى Windows فقط، وإن كان الجهاز مغلقًا وقت الموعد تعمل عند أول فرصة. إن كانت الوجهة على Google Drive فيجب أن يكون شغّالًا وقتها، وإلا يُسجَّل خطأ ولا يُنسخ شيء.

## شكل النسخ

```
GitBackups\
  backup-state.json                      <- حالة آخر نسخة لكل مستودع (لا تحذفه)
  <الحساب>\<المستودع>\
    <المستودع>_2026-11-16_213000_a1b2c3d.zip
```

داخل كل ZIP:

- `source\` ملفات الفرع الرئيسي وقت النسخ.
- `<المستودع>.bundle` التاريخ الكامل: كل الـ commits والفروع والـ tags.
- `RESTORE.txt` خطوات الاستعادة.

## الاستعادة

```powershell
git clone sunbula.bundle sunbula
```

## ما لا تشمله النسخة

- Issues و Pull Requests و Wiki و Releases (ليست جزءًا من مستودع git).
- ملفات Git LFS الفعلية (تُحفظ مؤشراتها فقط).
- المستودع المحذوف من الحساب أو الذي أُزيلت علامته: تبقى نسخه السابقة كما هي.

السجلات في `work\logs`.

## ملاحظة عند تعديل `backup-gui.ps1`

يجب أن يبقى محفوظًا بترميز "UTF-8 with BOM"، وإلا يظهر النص العربي في النافذة مشوّهًا.

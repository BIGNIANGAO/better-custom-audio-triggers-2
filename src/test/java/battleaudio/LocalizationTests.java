package battleaudio;

import battleaudio.localization.I18n;
import battleaudio.model.Config;
import com.megacrit.cardcrawl.core.Settings;
import java.util.*;

public final class LocalizationTests {
    private static int checks;
    private static final String[] MUSIC_PACK_TEXT={
        "Music packs", "Copy as new music pack", "New music pack", "No music packs yet",
        "Music pack copied as an independent copy", "Could not copy the music pack", "Copy this subscribed pack before editing",
        "Subscribed packs are read-only. Copy this pack before editing; the original still receives upstream updates.",
        "Workshop package sync completed: installed/updated %d, removed %d.", "Pack switch", "Pack name",
        "Export for Workshop", "Please fix the highlighted fields before exporting the music pack.",
        "Exported (not uploaded):", "Could not open the folder automatically; copy the path above.",
        "Preparing Workshop export...", "Pack version", "Root list",
        "Package version is release metadata; it does not migrate the rule schema.",
        "Package version increased; this only marks package contents for Workshop updates.",
        "Subscribed packs are read-only. Create a local package to add your own rules.",
        "Rename music pack", "Increase package version", "Remove music pack (move rules to root)",
        "Move to root list", "Move to", "No other editable packages", "Remove music pack",
        "This removes the package grouping and moves %d rules to the root list. Rules and audio files are kept.",
        "Remove package", "Rename rule", "Delete rule",
        "This permanently removes the rule. Audio is reclaimed only when no remaining rule references it and ownership checks pass.",
        "Cancel", "Rename", "Delete", "Enable rule", "Disable rule", "Delete music pack", "Delete package",
        "This permanently deletes the package and its %d rules. Package files cannot be recovered.",
        "Deleting music pack...", "Copying music pack...",
        "Subscribed pack edits may be replaced by Steam updates. Copy the pack to keep your changes.",
        "Remove cross-package rule links before moving this rule."
    };
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void run() {
        Map<String,String> source=I18n.catalog("zhs");
        Settings.GameLanguage saved=Settings.language;
        try {
            check(I18n.languages().length==24,"All 24 game languages are supported");
            for(String language:I18n.languages()) {
                Map<String,String> entries=I18n.catalog(language);
                check(entries.keySet().equals(source.keySet()),language+" has a complete catalog");
                Map<String,String> workshop=I18n.workshopCatalog(language);
                check(workshop.keySet().equals(I18n.workshopCatalog("eng").keySet()),language+" has a complete publishing catalog");
                Settings.language=Settings.GameLanguage.valueOf(language.toUpperCase(Locale.ROOT));
                check(I18n.language().equals(language),"Follows the game's current language: "+language);
                if(!language.equals("eng")) {
                    for(String key:MUSIC_PACK_TEXT)
                        check(!I18n.text(key).equals(I18n.text(key,"eng")),language+" translates music-pack control: "+key);
                    for(String key:new String[]{"Publish music pack","Workshop publishing","Select an exported music pack",
                            "Open containing folder","Edit and preview publication","Editable description","Full description preview",
                            "Visibility","Friends only","Default cover","Choose local image","Publication action",
                            "Create new music pack","Update published music pack","Confirm publication","Confirm and upload",
                            "Uploading...","Workshop upload completed","Workshop upload failed","Existing Workshop item ID or link",
                            "Verify ownership","Link this item","Open Workshop item","Upload progress"})
                        check(!I18n.workshopText(key).equals(key),language+" translates publishing control: "+key);
                    for(String key:new String[]{"Steam did not return an item ID","Steam returned a different Workshop item","Workshop operation failed"})
                        check(!I18n.workshopText(key).equals(key),language+" translates publishing failure: "+key);
                }
                for(Map.Entry<String,String> entry:entries.entrySet()) {
                    check(entry.getValue()!=null && !entry.getValue().trim().isEmpty(),language+" nonempty translation: "+entry.getKey());
                    check(I18n.text(entry.getKey()).equals(entry.getValue()),language+" actually selects its catalog");
                    if(entry.getKey().contains("≥"))check(entry.getValue().contains("≥"),language+" retains >= operator");
                    if(entry.getKey().contains("≤"))check(entry.getValue().contains("≤"),language+" retains <= operator");
                }
                for(Map.Entry<String,String> entry:workshop.entrySet()) {
                    check(entry.getValue()!=null && !entry.getValue().trim().isEmpty(),language+" nonempty publishing translation: "+entry.getKey());
                    check(I18n.workshopText(entry.getKey()).equals(entry.getValue()),language+" selects publishing translation: "+entry.getKey());
                    String english=I18n.workshopCatalog("eng").get(entry.getKey());
                    if(!language.equals("eng") && english.length()>10 && !english.equals("Steam Workshop"))
                        check(!entry.getValue().equals(english),language+" contains an English publishing placeholder: "+entry.getKey());
                }
                String filename="C:\\Audio\\工程配乐-测试.mp3";
                check(I18n.workshopText("Export file is missing: "+filename).endsWith(filename),language+" preserves export paths in errors");
                for(String key:new String[]{"音乐包配置缺失","订阅音乐包为只读；请先复制为新音乐包，再导出发布",
                        "音乐包没有可播放规则，请先配置音频再导出","音乐包导出目录不能是符号链接","工坊项目 ID 无效"}) {
                    check(entries.containsKey(key),language+" has music-pack error translation: "+key);
                    if(!language.equals("zhs"))check(!I18n.error(key).equals(key),language+" translates music-pack error: "+key);
                }
                String created="Item was created, but its local association could not be saved: ";
                check(I18n.workshopText(created+"12345: "+filename).equals(workshop.get(created)+"12345: "+filename),language+" preserves created item ID and path");
                String dependency="Upload completed, but the required item could not be added: ";
                check(I18n.workshopText(dependency+"Steam result: 42").equals(workshop.get(dependency)+"Steam result: 42"),language+" distinguishes upload success from dependency failure");
                check(I18n.error("本地音乐包设置无效："+filename).endsWith(filename),language+" preserves music-pack paths in errors");
                String missing=I18n.error("本地音乐包 my-pack 缺少 2 个音频文件");
                check(missing.contains("my-pack") && missing.contains("2") && missing.contains(I18n.text("Missing audio files")),language+" translates missing-audio warnings without changing identifiers");
                Config fresh=new Config();
                if(!language.equals("eng"))check(!I18n.text("My music pack").equals(I18n.text("My music pack","eng")),language+" translates default music-pack name");
                battleaudio.model.MusicPack defaultPack=fresh.ensureLocalPack(I18n.text("My music pack"));
                check(defaultPack.name.equals(I18n.text("My music pack")),language+" localizes a newly created default music-pack name");
                defaultPack.name="Personal Pack";
                check(fresh.ensureLocalPack("another name").name.equals("Personal Pack"),language+" preserves an existing personal music-pack name");
                Config copied=battleaudio.storage.MusicPackages.copyAsLocal(fresh,defaultPack.id,I18n.text(" 副本"));
                check(copied.packs.size()==2 && copied.packs.get(1).name.equals("Personal Pack"+I18n.text(" 副本"))
                        && fresh.packs.get(0).name.equals("Personal Pack"),language+" localizes only the new copy suffix");
                check(String.format(I18n.locale(),I18n.text("This permanently deletes the package and its %d rules. Package files cannot be recovered."),17).contains("17"),language+" retains permanent-deletion count");
                String synced=String.format(I18n.locale(),I18n.text("Workshop package sync completed: installed/updated %d, removed %d."),17,29);
                check(synced.contains("17") && synced.contains("29"),language+" retains both synchronization counts");
                check(I18n.text("This removes the package grouping and moves %d rules to the root list. Rules and audio files are kept.").contains("music-packs/.removed-packages"),language+" preserves the original recovery path");
                check(String.format(Locale.ROOT,I18n.text("%.2f 秒"),1.25).contains("1.25"),language+" retains valid number formatting");
                String file="工程配乐-测试.mp3";
                check(I18n.error("内置音频文件缺失："+file).endsWith(file),language+" preserves file names in errors");
                Config config=new Config();Config.Rule rule=new Config.Rule();rule.name="我自己的中文规则 / My Rule";rule.triggers=EnumSet.of(Config.Trigger.LOW_HEALTH);config.rules.add(rule);
                String before=new com.google.gson.Gson().toJson(config);I18n.text("低血量 ≤ 阈值");
                check(before.equals(new com.google.gson.Gson().toJson(config)),language+" does not mutate rule names or IDs");
            }
            check(I18n.text("关闭","ENG").equals("Close"),"Reviewed English close label");
            check(I18n.text("关闭","unknown").equals("Close"),"Unknown language has English fallback");
            check(I18n.text("not a UI key","fra").equals("not a UI key"),"Unknown text is preserved");
            check(I18n.text("更好的自定义音频触发","zhs").equals("更好的自定义音频触发"),"Approved simplified Chinese name is unchanged");
            battleaudio.ui.LocalizationChoiceTests.run();
        } finally {Settings.language=saved;}
        System.out.println("PASS: "+checks+" localization, format, error and persistence checks across 24 languages");
    }
}

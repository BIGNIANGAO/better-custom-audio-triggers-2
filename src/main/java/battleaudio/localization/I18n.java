package battleaudio.localization;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.megacrit.cardcrawl.core.Settings;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Translates UI text only. Stored IDs, rule names and imported file names remain untouched. */
public final class I18n {
    private static final String[] LANGUAGES = {"eng","dut","epo","ptb","zhs","zht","fin","fra","deu","gre","ind","ita","jpn","kor","nor","pol","rus","spa","srp","srb","tha","tur","ukr","vie"};
    private static final Map<String,Map<String,String>> CATALOGS = new HashMap<>();
    private static final Map<String,Map<String,String>> WORKSHOP_CATALOGS = new HashMap<>();
    private I18n() { }
    public static String[] languages() { return LANGUAGES.clone(); }
    public static String language() { return code(Settings.language == null ? "eng" : Settings.language.name()); }
    public static String code(String language) {
        String value = language == null ? "eng" : language.toLowerCase(Locale.ROOT);
        for (String supported : LANGUAGES) if (supported.equals(value)) return value;
        return "eng";
    }
    public static String text(String source) { return text(source, language()); }
    public static String text(String source, String language) {
        if (source == null) return "";
        String value = catalog(language).get(source);
        if (value == null) value = catalog("eng").get(source);
        return value == null ? source : value;
    }
    public static String[] text(String[] sources) {
        String[] values = new String[sources.length];
        for (int index=0; index<sources.length; index++) values[index]=text(sources[index]);
        return values;
    }
    public static synchronized Map<String,String> workshopCatalog(String language) {
        String code=code(language);
        if (!WORKSHOP_CATALOGS.containsKey(code)) {
            String path="/battleaudio/workshop-localization/"+code+".json";
            try (InputStream input=I18n.class.getResourceAsStream(path)) {
                if (input==null) throw new IllegalStateException("Missing Workshop localization: "+code);
                Map<String,String> entries=new Gson().fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),new TypeToken<LinkedHashMap<String,String>>(){}.getType());
                if (entries==null) throw new IllegalStateException("Invalid Workshop localization: "+code);
                WORKSHOP_CATALOGS.put(code,Collections.unmodifiableMap(entries));
            } catch (IOException ex) { throw new IllegalStateException("Cannot read Workshop localization: "+code,ex); }
        }
        return WORKSHOP_CATALOGS.get(code);
    }
    public static String workshopText(String source) {
        if (source==null) return "";
        Map<String,String> localized=workshopCatalog(language());
        String value=localized.get(source);
        if(value!=null)return value;
        String best=null;
        for(String key:workshopCatalog("eng").keySet()) {
            if((key.endsWith(": ") || key.endsWith("：")) && source.startsWith(key)
                    && (best==null || key.length()>best.length()))best=key;
        }
        if(best!=null)return localized.get(best)+source.substring(best.length());
        return error(source);
    }
    public static String error(String source) {
        if (source == null) return "";
        if(source.indexOf('\n')>=0) {
            String[] lines=source.split("\\n",-1);
            for(int i=0;i<lines.length;i++)lines[i]=error(lines[i]);
            return String.join("\n",lines);
        }
        String warning=packageWarning(source);
        if(warning!=null)return warning;
        if (catalog("zhs").containsKey(source)) return text(source);
        String best=null;
        for (String key : catalog("zhs").keySet()) {
            // Translate known error prefixes, without translating names or paths in their suffixes.
            if (key.length()>=4 && (key.endsWith("：") || key.endsWith("；")) && source.startsWith(key)
                    && (best==null || key.length()>best.length())) best=key;
        }
        return best==null ? source : text(best)+error(source.substring(best.length()));
    }
    private static String packageWarning(String source) {
        java.util.regex.Matcher missing=java.util.regex.Pattern.compile("^(本地音乐包|工坊音乐包) (.*?) 缺少 ([0-9]+) 个音频文件$").matcher(source);
        if(missing.matches())return text(missing.group(1).equals("本地音乐包") ? "Local music pack" : "Workshop music pack")
                +" "+missing.group(2)+": "+text("Missing audio files")+" "+missing.group(3);
        String[][] cases={
                {"工坊音乐包 "," 校验失败：","Workshop music pack","Validation failed"},
                {"工坊音乐包 "," 未更新：","Workshop music pack","Not updated"},
                {"已取消订阅的音乐包 "," 未能移除设置：","Unsubscribed music pack","Could not remove settings"},
                {"本地音乐包无法读取：","：","Local music pack","Validation failed"}
        };
        for(String[] entry:cases) {
            if(!source.startsWith(entry[0]))continue;
            int separator=source.indexOf(entry[1],entry[0].length());
            if(separator<0)continue;
            return text(entry[2])+" "+source.substring(entry[0].length(),separator)+": "+text(entry[3])+": "
                    +error(source.substring(separator+entry[1].length()));
        }
        return null;
    }
    public static synchronized Map<String,String> catalog(String language) {
        String code=code(language);
        if (!CATALOGS.containsKey(code)) {
            String path="/battleaudio/localization/"+code+".json";
            try (InputStream input=I18n.class.getResourceAsStream(path)) {
                if (input==null) throw new IllegalStateException("Missing audio localization: "+code);
                Map<String,String> entries=new Gson().fromJson(new InputStreamReader(input,StandardCharsets.UTF_8),new TypeToken<LinkedHashMap<String,String>>(){}.getType());
                if (entries==null || entries.isEmpty()) throw new IllegalStateException("Empty audio localization: "+code);
                CATALOGS.put(code,Collections.unmodifiableMap(entries));
            } catch (IOException ex) { throw new IllegalStateException("Cannot read audio localization: "+code,ex); }
        }
        return CATALOGS.get(code);
    }
    public static Locale locale() {
        String[] tags={"en","nl","eo","pt-BR","zh-CN","zh-TW","fi","fr","de","el","id","it","ja","ko","nb","pl","ru","es","sr-Latn","sr-Cyrl","th","tr","uk","vi"};
        String current=language();
        for(int index=0;index<LANGUAGES.length;index++)if(LANGUAGES[index].equals(current))return Locale.forLanguageTag(tags[index]);
        return Locale.ENGLISH;
    }
}

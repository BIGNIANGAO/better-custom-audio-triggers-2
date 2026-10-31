package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.megacrit.cardcrawl.core.Settings;
import java.util.*;

/** Real OpenGL font generation for every locale, with fresh private faces and late user text. */
public final class AllLanguageFontSmoke {
    public static void run(Config config)throws Exception {
        Settings.GameLanguage saved=Settings.language;int total=0;
        java.lang.reflect.Method pixels=LocalizationUiSmoke.class.getDeclaredMethod("atlasChecks",BitmapFont.class);pixels.setAccessible(true);
        try {
            for(String language:I18n.languages()) {
                Settings.language=Settings.GameLanguage.valueOf(language.toUpperCase(Locale.ROOT));long began=System.nanoTime();
                BitmapFont font=LocalizedFont.create(config);
                try {
                    double initializeMs=(System.nanoTime()-began)/1000000d;
                    if(font.getData().hasGlyph('\n') || font.getData().hasGlyph('\r'))
                        throw new AssertionError(language+" exposes line breaks as printable glyphs");
                    for(String text:I18n.catalog(language).values())for(char c:text.toCharArray())
                        if(!Character.isWhitespace(c) && !font.getData().hasGlyph(c))throw new AssertionError(language+" missing UI glyph U+"+Integer.toHexString(c));
                    for(String text:I18n.workshopCatalog(language).values())for(char c:text.toCharArray())
                        if(!Character.isWhitespace(c) && !font.getData().hasGlyph(c))throw new AssertionError(language+" missing Workshop UI glyph U+"+Integer.toHexString(c));
                    for(char c:"龘曌鬱갂힣あวะ".toCharArray())if(!font.getData().hasGlyph(c))throw new AssertionError(language+" cannot load new user glyph");
                    new GlyphLayout(font,"龘曌鬱갂힣あวะ");pixels.invoke(null,font);
                    if(initializeMs>650)throw new AssertionError(language+" font initialization is still slow: "+initializeMs+"ms");
                    System.out.println("PASS: lazy font "+language+", cold "+Math.round(initializeMs)+" ms, complete UI and late user glyphs, exact atlas pixels");total++;
                } finally {font.dispose();font.dispose();}
            }
        } finally {Settings.language=saved;}
        System.out.println("ALL LANGUAGE FONT CHECKS PASSED: "+total);
    }
}

package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.megacrit.cardcrawl.core.Settings;
import java.util.*;

public final class LocalizationUiSmoke {
    private static Object field(Object target,String name)throws Exception{java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static boolean contains(Group group,String text){for(Actor actor:group.getChildren()){if(actor instanceof Label && ((Label)actor).getText().toString().equals(text))return true;if(actor instanceof Group && contains((Group)actor,text))return true;}return false;}
    private static void atlasChecks(com.badlogic.gdx.graphics.g2d.BitmapFont font)throws Exception {
        String language=I18n.language();
        String path=language.equals("zhs")?"font/zhs/NotoSansMonoCJKsc-Regular.otf":language.equals("zht")?"font/zht/NotoSansCJKtc-Regular.otf":language.equals("jpn")?"font/jpn/NotoSansCJKjp-Regular.otf":language.equals("kor")?"font/kor/GyeonggiCheonnyeonBatangBold.ttf":language.equals("tha")?"font/tha/CSChatThaiUI.ttf":"font/NotoSans-Regular.ttf";
        com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator generator=new com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator(com.badlogic.gdx.Gdx.files.internal(path));
        com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter parameters=new com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter();
        parameters.size=34;parameters.characters="A0s自";
        com.badlogic.gdx.graphics.g2d.BitmapFont reference=generator.generateFont(parameters);
        try {
            for(char character:new char[]{'A','0','s','自'}) {
                com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph expected=reference.getData().getGlyph(character);
                if(expected==null || expected==reference.getData().missingGlyph)continue;
                com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph actual=font.getData().getGlyph(character);
                com.badlogic.gdx.graphics.Texture actualTexture=font.getRegion(actual.page).getTexture(),expectedTexture=reference.getRegion(expected.page).getTexture();
                com.badlogic.gdx.graphics.TextureData actualData=actualTexture.getTextureData(),expectedData=expectedTexture.getTextureData();
                if(!actualData.isPrepared())actualData.prepare();if(!expectedData.isPrepared())expectedData.prepare();
                com.badlogic.gdx.graphics.Pixmap actualPixels=actualData.consumePixmap(),expectedPixels=expectedData.consumePixmap();
                try {
                    int actualX=Math.round(Math.min(actual.u,actual.u2)*actualTexture.getWidth()),actualY=Math.round(Math.min(actual.v,actual.v2)*actualTexture.getHeight());
                    int expectedX=Math.round(Math.min(expected.u,expected.u2)*expectedTexture.getWidth()),expectedY=Math.round(Math.min(expected.v,expected.v2)*expectedTexture.getHeight());
                    for(int y=0;y<expected.height;y++)for(int x=0;x<expected.width;x++)
                        if(actualPixels.getPixel(actualX+x,actualY+y)!=expectedPixels.getPixel(expectedX+x,expectedY+y))throw new AssertionError("Multi-page font raster mismatch for "+character+" in "+language);
                } finally {if(actualData.disposePixmap())actualPixels.dispose();if(expectedData.disposePixmap())expectedPixels.dispose();}
            }
        } finally {reference.dispose();generator.dispose();}
    }
    public static void run(SettingsScreen screen)throws Exception {
        String requested=System.getProperty("battleaudio.testLanguage");
        if(requested!=null && !requested.equals(Settings.language.name()))throw new AssertionError("Game did not load requested language: "+requested);
        Stage stage=(Stage)field(screen,"stage");Table root=(Table)field(screen,"root");root.validate();
        if(!contains(root,I18n.text("更好的自定义音频触发")))throw new AssertionError("Localized settings title is missing");
        if(!contains(root,I18n.text("Music packs")))throw new AssertionError("Localized music-pack list title is missing");
        Skin skin=(Skin)field(screen,"skin");com.badlogic.gdx.graphics.g2d.BitmapFont font=skin.getFont("settings-font");
        atlasChecks(font);
        String all=String.join("",I18n.catalog(I18n.language()).values())
                +String.join("",I18n.workshopCatalog(I18n.language()).values())+"中文规则名，测试音频，自定义名称，日本語，한글";
        Set<Character> checked=new HashSet<>();
        for(char character:all.toCharArray())if(!Character.isWhitespace(character) && checked.add(character) && !font.getData().hasGlyph(character))throw new AssertionError(I18n.language()+" missing glyph U+"+Integer.toHexString(character));
        com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph visible=font.getData().getGlyph('A');
        if(visible.u==visible.u2 || visible.v==visible.v2)throw new AssertionError("Glyph has no renderable atlas coordinates");
        Table editor=(Table)field(screen,"editor");editor.validate();
        for(Actor child:editor.getChildren())if(child instanceof Label && ((Label)child).getText().toString().equals(I18n.text("触发概率 (%)")) && child.getWidth()<200)throw new AssertionError("Localized label column collapsed");
        Config config=(Config)field(screen,"draft");String before=new com.google.gson.Gson().toJson(config);
        if(config.packs.isEmpty() || !config.packs.get(0).name.equals(I18n.text("My music pack")))
            throw new AssertionError("New default music-pack name is not localized");
        TextButton publish=stage.getRoot().findActor("open-workshop-publisher");
        if(publish==null || !publish.getText().toString().equals(I18n.workshopText("Publish music pack")))
            throw new AssertionError("Workshop publisher entry is not localized");
        publish.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
        Object wizard=field(screen,"workshopWizard");
        if(wizard==null)throw new AssertionError("Localized Workshop wizard did not open");
        Label wizardHeading=(Label)field(wizard,"heading");
        if(!wizardHeading.getText().toString().contains(I18n.workshopText("Select an exported music pack")))
            throw new AssertionError("Workshop wizard heading is not localized");
        long readyUntil=System.currentTimeMillis()+10000;
        while((Boolean)field(wizard,"working") && System.currentTimeMillis()<readyUntil) { Thread.sleep(10); screen.update(); }
        if((Boolean)field(wizard,"working"))throw new AssertionError("Workshop export scan did not finish");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        com.badlogic.gdx.graphics.Pixmap wizardPixels=com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0,0,com.badlogic.gdx.Gdx.graphics.getWidth(),com.badlogic.gdx.Gdx.graphics.getHeight());
        com.badlogic.gdx.graphics.PixmapIO.PNG png=new com.badlogic.gdx.graphics.PixmapIO.PNG();png.setFlipY(true);
        try { png.write(com.badlogic.gdx.Gdx.files.local("workshop-localized-"+I18n.language()+".png"),wizardPixels); }
        finally { png.dispose(); wizardPixels.dispose(); }
        TextButton closeWizard=stage.getRoot().findActor("workshop-close");
        if(closeWizard==null)throw new AssertionError("Workshop wizard close control is missing");
        closeWizard.fire(new com.badlogic.gdx.scenes.scene2d.utils.ChangeListener.ChangeEvent());
        if(field(screen,"workshopWizard")!=null)throw new AssertionError("Workshop wizard did not close");
        SelectBox<?> categories=stage.getRoot().findActor("trigger-category");
        if(categories!=null && !categories.getItems().get(0).equals(I18n.text("通用")))throw new AssertionError("Category menu not localized");
        java.util.List<FilterOptions.Option> cards=FilterOptions.cards();
        FilterOptions.Option strike=cards.stream().filter(option->option.id.equals("Strike_R")).findFirst().get();
        if(!strike.name.startsWith(com.megacrit.cardcrawl.helpers.CardLibrary.cards.get("Strike_R").name))throw new AssertionError("Vanilla list does not match the game language");
        if(!strike.matches(strike.name))throw new AssertionError("Cannot search for a localized vanilla card");
        for(com.evacipated.cardcrawl.modthespire.ModInfo info:com.evacipated.cardcrawl.modthespire.Loader.MODINFOS)
            if("battleaudio".equals(info.ID) && !info.Name.equals(I18n.text("更好的自定义音频触发")))throw new AssertionError("In-game mod list name not localized");
        screen.render((com.badlogic.gdx.graphics.g2d.SpriteBatch)stage.getBatch());
        com.badlogic.gdx.graphics.Pixmap pixels=com.badlogic.gdx.utils.ScreenUtils.getFrameBufferPixmap(0,0,com.badlogic.gdx.Gdx.graphics.getWidth(),com.badlogic.gdx.Gdx.graphics.getHeight());
        try {
            int visiblePixels=0;
            for(int y=Math.max(0,pixels.getHeight()-80);y<pixels.getHeight()-15;y++)
                for(int x=pixels.getWidth()/12;x<pixels.getWidth()/2;x++) {
                    int color=pixels.getPixel(x,y);int red=(color>>>24)&255,green=(color>>>16)&255,blue=(color>>>8)&255;
                    if(red>150 && green>100 && blue<130 && red>blue*1.25f)visiblePixels++;
                }
            if(visiblePixels<30)throw new AssertionError("Localized title did not render visible text");
        } finally {pixels.dispose();}
        if(!before.equals(new com.google.gson.Gson().toJson(config)))throw new AssertionError("Rendering localized UI mutated existing configuration");
        System.out.println("PASS: "+I18n.language()+" real-game UI, "+checked.size()+" glyphs, localized card search, mod name and unchanged configuration");
    }
}

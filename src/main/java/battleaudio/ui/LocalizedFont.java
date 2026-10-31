package battleaudio.ui;

import battleaudio.localization.I18n;
import battleaudio.model.Config;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFont.Glyph;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.utils.Array;
import java.util.*;

/** Small initial atlases; new user text requests glyphs lazily on the render thread. */
final class LocalizedFont {
    private LocalizedFont() { }
    /** The bundled FreeType generator always records the last page for a packed glyph. */
    private static final class LastPageStrategy extends com.badlogic.gdx.graphics.g2d.PixmapPacker.SkylineStrategy {
        @Override public com.badlogic.gdx.graphics.g2d.PixmapPacker.Page pack(com.badlogic.gdx.graphics.g2d.PixmapPacker packer,String name,com.badlogic.gdx.math.Rectangle rectangle) {
            Array<com.badlogic.gdx.graphics.g2d.PixmapPacker.Page> pages=packer.getPages();
            Array<com.badlogic.gdx.graphics.g2d.PixmapPacker.Page> saved=new Array<>(pages);
            pages.clear();if(saved.size>0)pages.add(saved.peek());
            try {
                com.badlogic.gdx.graphics.g2d.PixmapPacker.Page selected=super.pack(packer,name,rectangle);
                Array<com.badlogic.gdx.graphics.g2d.PixmapPacker.Page> used=new Array<>(pages);
                pages.clear();pages.addAll(saved);
                for(int index=saved.size>0?1:0;index<used.size;index++)pages.add(used.get(index));
                return selected;
            } catch(RuntimeException ex){pages.clear();pages.addAll(saved);throw ex;}
        }
    }
    private static final class Source {
        final String path,characters;
        FreeTypeFontGenerator generator;
        com.badlogic.gdx.graphics.g2d.PixmapPacker packer;
        BitmapFont font;
        Source(String path,String characters){this.path=path;this.characters=characters;}
        BitmapFont font() {
            if(font==null) {
                generator=new FreeTypeFontGenerator(Gdx.files.internal(path));
                packer=new com.badlogic.gdx.graphics.g2d.PixmapPacker(1024,1024,com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888,1,false,new LastPageStrategy());
                FreeTypeFontGenerator.FreeTypeFontParameter parameters=new FreeTypeFontGenerator.FreeTypeFontParameter();
                parameters.size=34;parameters.characters=characters;parameters.incremental=true;parameters.packer=packer;
                font=generator.generateFont(parameters);font.setOwnsTexture(true);
            }
            return font;
        }
        void dispose(){if(font!=null)font.dispose();if(generator!=null)generator.dispose();if(packer!=null)packer.dispose();}
    }
    static BitmapFont create(Config config) {
        StringBuilder needed=new StringBuilder(FreeTypeFontGenerator.DEFAULT_CHARS);
        for(String value:I18n.catalog(I18n.language()).values())needed.append(value);
        for(String value:I18n.workshopCatalog(I18n.language()).values())needed.append(value);
        needed.append(new com.google.gson.Gson().toJson(config));needed.append("≥≤→…·");
        for(String kind:new String[]{"cards","powers","relics","events","monsters","potions"}) {
            try(java.io.InputStream input=com.megacrit.cardcrawl.core.CardCrawlGame.class.getResourceAsStream("/localization/"+I18n.language()+"/"+kind+".json")) {
                if(input!=null) {
                    com.google.gson.JsonObject entries=new com.google.gson.Gson().fromJson(new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8),com.google.gson.JsonObject.class);
                    for(Map.Entry<String,com.google.gson.JsonElement> entry:entries.entrySet()) {
                        com.google.gson.JsonElement name=entry.getValue().getAsJsonObject().get("NAME");
                        if(name!=null && name.isJsonPrimitive())needed.append(name.getAsString());
                    }
                }
            }catch(java.io.IOException ex){throw new IllegalStateException("Cannot read native font names",ex);}
        }
        Set<Character> unique=new LinkedHashSet<>();for(char character:needed.toString().toCharArray())unique.add(character);
        StringBuilder characters=new StringBuilder();for(char character:unique)characters.append(character);
        String language=I18n.language();
        String primary=language.equals("zhs")?"font/zhs/NotoSansMonoCJKsc-Regular.otf":language.equals("zht")?"font/zht/NotoSansCJKtc-Regular.otf":language.equals("jpn")?"font/jpn/NotoSansCJKjp-Regular.otf":language.equals("kor")?"font/kor/GyeonggiCheonnyeonBatangBold.ttf":language.equals("tha")?"font/tha/CSChatThaiUI.ttf":"font/NotoSans-Regular.ttf";
        final List<Source> sources=new ArrayList<>();
        for(String path:new LinkedHashSet<>(Arrays.asList(primary,"font/zhs/NotoSansMonoCJKsc-Regular.otf","font/tha/CSChatThaiUI.ttf")))sources.add(new Source(path,characters.toString()));
        final BitmapFont.BitmapFontData base=sources.get(0).font().getData();
        final Array<TextureRegion> regions=new Array<>();
        final Map<TextureRegion,Integer> pageNumbers=new IdentityHashMap<>();
        BitmapFont.BitmapFontData data=new BitmapFont.BitmapFontData() {
            private final Set<Character> unsupported=new HashSet<>();
            @Override public Glyph getGlyph(char character) {
                Glyph cached=super.getGlyph(character);if(cached!=null)return cached;
                if(unsupported.contains(character))return missingGlyph;
                for(int index=0;index<sources.size();index++) {
                    BitmapFont source=sources.get(index).font();Glyph original=source.getData().getGlyph(character);
                    if(original==null || original==source.getData().missingGlyph)continue;
                    // Flush any newly generated page before copying authoritative atlas UVs.
                    new GlyphLayout(source,String.valueOf(character));original=source.getData().getGlyph(character);
                    TextureRegion region=source.getRegion(original.page);Integer page=pageNumbers.get(region);
                    if(page==null){page=regions.size;regions.add(region);pageNumbers.put(region,page);}
                    float ratio=index==0?1:base.capHeight/source.getData().capHeight;
                    Glyph glyph=new Glyph();glyph.id=original.id;glyph.srcX=original.srcX;glyph.srcY=original.srcY;
                    glyph.width=Math.round(original.width*ratio);glyph.height=Math.round(original.height*ratio);
                    glyph.xoffset=Math.round(original.xoffset*ratio);glyph.yoffset=Math.round(original.yoffset*ratio);
                    glyph.xadvance=Math.round(original.xadvance*ratio);glyph.page=page;glyph.kerning=original.kerning;glyph.fixedWidth=original.fixedWidth;
                    glyph.u=original.u;glyph.v=original.v;glyph.u2=original.u2;glyph.v2=original.v2;
                    setGlyph(character,glyph);return glyph;
                }
                unsupported.add(character);return missingGlyph;
            }
            @Override public boolean hasGlyph(char character){
                // TextField replaces line breaks with spaces when calculating character positions.
                if(character=='\n' || character=='\r')return false;
                Glyph glyph=getGlyph(character);return glyph!=null && !unsupported.contains(character);
            }
        };
        data.lineHeight=base.lineHeight;data.capHeight=base.capHeight;data.ascent=base.ascent;data.descent=base.descent;
        data.down=base.down;data.spaceWidth=base.spaceWidth;data.xHeight=base.xHeight;data.blankLineScale=base.blankLineScale;
        data.padTop=base.padTop;data.padBottom=base.padBottom;data.padLeft=base.padLeft;data.padRight=base.padRight;
        data.flipped=base.flipped;data.breakChars=base.breakChars;data.getGlyph(' ');data.getGlyph('A');data.missingGlyph=data.getGlyph('?');
        return new BitmapFont(data,regions,false) {
            private boolean disposed;
            // Glyphs already carry their source atlas UVs, including subsequently added pages.
            @Override protected void load(BitmapFont.BitmapFontData ignored) { }
            @Override public void dispose(){if(disposed)return;disposed=true;super.dispose();for(Source source:sources)source.dispose();}
        };
    }
}

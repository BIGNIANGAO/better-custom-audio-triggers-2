package battleaudio.ui;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.*;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.ImageMaster;

/** Only generated textures/fonts belong to the skin; native textures stay owned by the game. */
final class SettingsSkin {
    private static final Color TEXT = new Color(.94f,.91f,.82f,1);
    private static final Color GOLD = new Color(.94f,.79f,.43f,1);
    private static final Color MUTED = new Color(.58f,.62f,.62f,1);
    private SettingsSkin() { }

    static void populate(Skin skin, battleaudio.model.Config config) {
        if (ImageMaster.OPTION_TOGGLE == null || ImageMaster.OPTION_SLIDER == null || ImageMaster.INPUT_SETTINGS_ROW == null)
            throw new IllegalStateException("Game option textures are not initialized");
        BitmapFont font=LocalizedFont.create(config);
        font.getData().setScale(26/font.getCapHeight()); font.getData().markupEnabled=false;
        skin.add("settings-font",font,BitmapFont.class);
        Texture pixel=texture(skin,"pixel",1,Color.WHITE,Color.WHITE);
        TextureRegionDrawable flat=new TextureRegionDrawable(new TextureRegion(pixel));
        skin.add("background",flat.tint(new Color(.055f,.08f,.09f,.98f)),Drawable.class);
        NinePatchDrawable overlay=new NinePatchDrawable(new NinePatch(new TextureRegion(ImageMaster.SETTINGS_BACKGROUND,968,280,614,297),28,28,28,28));
        overlay.setMinWidth(0); overlay.setMinHeight(0); skin.add("overlay-background",overlay,Drawable.class);
        skin.add("candidate-background",panel(skin,"candidate-panel",new Color(.10f,.16f,.18f,.92f),
                new Color(.31f,.40f,.40f,1)),Drawable.class);

        Drawable normal=panel(skin,"button-up",new Color(.13f,.20f,.24f,1),new Color(.44f,.49f,.46f,1));
        Drawable hover=panel(skin,"button-over",new Color(.20f,.29f,.32f,1),GOLD);
        Drawable down=panel(skin,"button-down",new Color(.07f,.13f,.16f,1),new Color(.66f,.55f,.30f,1));
        Drawable selected=panel(skin,"button-selected",new Color(.22f,.29f,.28f,1),GOLD);
        Drawable disabled=panel(skin,"button-disabled",new Color(.10f,.14f,.16f,1),new Color(.24f,.29f,.29f,1));
        TextButton.TextButtonStyle button=new TextButton.TextButtonStyle(normal,down,null,font);
        button.over=hover; button.disabled=disabled; button.fontColor=TEXT;
        button.overFontColor=GOLD; button.downFontColor=Color.WHITE; button.disabledFontColor=MUTED;
        skin.add("default",button);
        TextButton.TextButtonStyle choice=new TextButton.TextButtonStyle(button);
        choice.checked=selected; choice.checkedOver=hover; choice.checkedFontColor=GOLD;
        choice.checkedOverFontColor=GOLD; skin.add("choice",choice);

        Drawable listNormal=panel(skin,"list-button-up",new Color(.12f,.18f,.21f,1),new Color(.34f,.41f,.41f,1),4,6,6,7,7);
        Drawable listHover=panel(skin,"list-button-over",new Color(.19f,.27f,.29f,1),GOLD,4,6,6,7,7);
        Drawable listDown=panel(skin,"list-button-down",new Color(.08f,.14f,.17f,1),new Color(.58f,.48f,.25f,1),4,6,6,7,7);
        Drawable listSelected=panel(skin,"list-button-selected",new Color(.21f,.28f,.27f,1),GOLD,4,6,6,7,7);
        Drawable listDisabled=panel(skin,"list-button-disabled",new Color(.10f,.14f,.16f,1),new Color(.25f,.30f,.30f,1),4,6,6,7,7);
        TextButton.TextButtonStyle listChoice=new TextButton.TextButtonStyle(listNormal,listDown,null,font);
        listChoice.over=listHover; listChoice.disabled=listDisabled; listChoice.checked=listSelected; listChoice.checkedOver=listHover;
        listChoice.fontColor=TEXT; listChoice.overFontColor=GOLD; listChoice.downFontColor=Color.WHITE;
        listChoice.checkedFontColor=GOLD; listChoice.checkedOverFontColor=GOLD; listChoice.disabledFontColor=MUTED;
        skin.add("list-choice",listChoice);

        Drawable disclosureNormal=panel(skin,"disclosure-up",new Color(.12f,.18f,.21f,1),new Color(.34f,.41f,.41f,1),4,4,4,6,6);
        Drawable disclosureHover=panel(skin,"disclosure-over",new Color(.19f,.27f,.29f,1),GOLD,4,4,4,6,6);
        Drawable disclosureDown=panel(skin,"disclosure-down",new Color(.08f,.14f,.17f,1),new Color(.58f,.48f,.25f,1),4,4,4,6,6);
        TextButton.TextButtonStyle disclosure=new TextButton.TextButtonStyle(disclosureNormal,disclosureDown,null,font);
        disclosure.over=disclosureHover; disclosure.fontColor=TEXT; disclosure.overFontColor=GOLD;
        disclosure.downFontColor=Color.WHITE; disclosure.disabledFontColor=MUTED; skin.add("disclosure",disclosure);

        Drawable menuNormal=panel(skin,"menu-button-up",new Color(.12f,.19f,.22f,1),new Color(.34f,.42f,.42f,1),5,6,6,6,6);
        Drawable menuHover=panel(skin,"menu-button-over",new Color(.21f,.29f,.30f,1),GOLD,5,6,6,6,6);
        Drawable menuDown=panel(skin,"menu-button-down",new Color(.08f,.14f,.17f,1),new Color(.60f,.50f,.28f,1),5,6,6,6,6);
        Drawable menuDisabled=panel(skin,"menu-button-disabled",new Color(.10f,.14f,.16f,1),new Color(.25f,.30f,.30f,1),5,6,6,6,6);
        TextButton.TextButtonStyle menu=new TextButton.TextButtonStyle(menuNormal,menuDown,null,font);
        menu.over=menuHover; menu.disabled=menuDisabled; menu.fontColor=TEXT; menu.overFontColor=GOLD;
        menu.downFontColor=Color.WHITE; menu.disabledFontColor=MUTED; skin.add("menu",menu);
        skin.add("menu-background",panel(skin,"menu-frame",new Color(.07f,.13f,.16f,.98f),new Color(.35f,.43f,.43f,1),6,8,8,8,8),Drawable.class);
        skin.add("default",new Label.LabelStyle(font,TEXT));

        Drawable off=region(ImageMaster.OPTION_TOGGLE,32,32,Color.WHITE);
        Drawable on=new Layered(off,region(ImageMaster.OPTION_TOGGLE_ON,32,32,Color.WHITE));
        CheckBox.CheckBoxStyle check=new CheckBox.CheckBoxStyle(off,on,font,TEXT);
        check.checkboxOver=region(ImageMaster.OPTION_TOGGLE,32,32,GOLD);
        check.checkboxOffDisabled=region(ImageMaster.OPTION_TOGGLE,32,32,MUTED);
        check.checkboxOnDisabled=new Layered(check.checkboxOffDisabled,region(ImageMaster.OPTION_TOGGLE_ON,32,32,MUTED));
        check.overFontColor=GOLD; check.disabledFontColor=MUTED; skin.add("default",check);

        Drawable input=panel(skin,"input",new Color(.17f,.24f,.27f,1),new Color(.42f,.50f,.48f,1));
        TextField.TextFieldStyle field=new TextField.TextFieldStyle(font,TEXT,flat.tint(GOLD),flat.tint(new Color(.48f,.40f,.21f,1)),input);
        field.cursor.setMinWidth(2); field.focusedBackground=panel(skin,"input-focused",new Color(.23f,.31f,.32f,1),GOLD);
        field.disabledBackground=panel(skin,"input-disabled",new Color(.12f,.17f,.19f,1),MUTED); field.disabledFontColor=MUTED;
        field.messageFont=font; field.messageFontColor=MUTED; skin.add("default",field);

        ScrollPane.ScrollPaneStyle scroll=new ScrollPane.ScrollPaneStyle();
        BaseDrawable rail=(BaseDrawable)panel(skin,"scroll-rail",new Color(.07f,.10f,.12f,1),new Color(.20f,.28f,.29f,1));
        rail.setMinWidth(20); rail.setMinHeight(0);
        scroll.vScroll=rail; scroll.vScrollKnob=scrollKnob(skin);
        skin.add("default",scroll);
        NinePatch sliderTrack=new NinePatch(ImageMaster.OPTION_SLIDER_BG,12,12,0,0);
        NinePatchDrawable sliderBg=new NinePatchDrawable(sliderTrack); sliderBg.setMinWidth(0); sliderBg.setMinHeight(24);
        Slider.SliderStyle slider=new Slider.SliderStyle(sliderBg,region(ImageMaster.OPTION_SLIDER,38,38,Color.WHITE));
        slider.knobOver=region(ImageMaster.OPTION_SLIDER,38,38,new Color(1,1,.75f,1));
        slider.knobDown=region(ImageMaster.OPTION_SLIDER,38,38,GOLD);
        slider.disabledBackground=new NinePatchDrawable(sliderTrack).tint(MUTED);
        slider.disabledKnob=region(ImageMaster.OPTION_SLIDER,38,38,MUTED); skin.add("default-horizontal",slider);

        List.ListStyle list=new List.ListStyle(font,GOLD,TEXT,selected); list.background=panel(skin,"dropdown-list",new Color(.12f,.19f,.22f,1),GOLD);
        skin.add("default",list);
        SelectBox.SelectBoxStyle select=new SelectBox.SelectBoxStyle(font,TEXT,new Dropdown(input,flat.tint(GOLD),flat.tint(MUTED)),scroll,list);
        select.backgroundOver=new Dropdown(panel(skin,"select-over",new Color(.23f,.31f,.32f,1),GOLD),flat.tint(GOLD),flat.tint(GOLD));
        select.backgroundOpen=new Dropdown(panel(skin,"select-open",new Color(.25f,.32f,.30f,1),GOLD),flat.tint(GOLD),flat.tint(GOLD));
        select.backgroundDisabled=new Dropdown(field.disabledBackground,flat.tint(MUTED),flat.tint(MUTED)); select.disabledFontColor=MUTED;
        skin.add("default",select);
    }

    private static Texture texture(Skin skin,String name,int size,Color fill,Color border) {
        Pixmap pixels=new Pixmap(size,size,Pixmap.Format.RGBA8888);
        pixels.setColor(border); pixels.fill();
        if(size>4) { pixels.setColor(fill); pixels.fillRectangle(2,2,size-4,size-4); }
        Texture texture=new Texture(pixels); pixels.dispose();
        skin.add(name,texture); return texture;
    }
    private static Drawable panel(Skin skin,String name,Color fill,Color border) {
        return panel(skin,name,fill,border,6,8,8,8,8);
    }
    private static Drawable panel(Skin skin,String name,Color fill,Color border,int cut,
            int left,int right,int top,int bottom) {
        Pixmap pixels=new Pixmap(24,24,Pixmap.Format.RGBA8888);
        for(int y=0;y<24;y++) for(int x=0;x<24;x++) {
            if(!inside(x,y,cut)) continue;
            boolean edge=!inside(x-1,y,cut)||!inside(x+1,y,cut)||!inside(x,y-1,cut)||!inside(x,y+1,cut);
            int grain=((x*37+y*71+x*y*13)&15)-7;
            Color source=edge?border:fill;
            // NinePatch stretches the center: keep it flat so grain stays in fixed-size corners.
            float shade=edge || x>=8 && x<16 || y>=8 && y<16 ? 0 : grain/1200f;
            pixels.setColor(Math.max(0,Math.min(1,source.r+shade)),Math.max(0,Math.min(1,source.g+shade)),
                    Math.max(0,Math.min(1,source.b+shade)),source.a);
            pixels.drawPixel(x,y);
        }
        Texture image=new Texture(pixels); pixels.dispose(); skin.add(name,image);
        NinePatchDrawable panel=new NinePatchDrawable(new NinePatch(image,left,right,top,bottom));
        panel.setLeftWidth(left+5); panel.setRightWidth(right+5); panel.setTopHeight(top+1); panel.setBottomHeight(bottom+1);
        panel.setMinWidth(0); panel.setMinHeight(0); return panel;
    }
    private static boolean inside(int x,int y,int cut) {
        return x>=0 && y>=0 && x<24 && y<24 && x+y>=cut && (23-x)+y>=cut
                && x+(23-y)>=cut && (23-x)+(23-y)>=cut;
    }
    private static Drawable scrollKnob(Skin skin) {
        Pixmap pixels=new Pixmap(20,36,Pixmap.Format.RGBA8888);
        for(int y=2;y<34;y++) for(int x=3;x<17;x++) {
            if(x+y<10 || (19-x)+y<10 || x+(35-y)<10 || (19-x)+(35-y)<10) continue;
            pixels.setColor(x==3||x==16||y==2||y==33 ? new Color(.45f,.34f,.12f,1) : GOLD);
            pixels.drawPixel(x,y);
        }
        Texture image=new Texture(pixels); pixels.dispose(); skin.add("scroll-knob",image);
        return new TextureRegionDrawable(new TextureRegion(image));
    }
    private static Drawable region(Texture texture,float width,float height,Color tint) {
        Drawable drawable=new TextureRegionDrawable(new TextureRegion(texture)).tint(tint);
        drawable.setMinWidth(width); drawable.setMinHeight(height); return drawable;
    }
    private static final class Layered extends BaseDrawable {
        final Drawable base,overlay;
        Layered(Drawable base,Drawable overlay) { super(base); this.base=base; this.overlay=overlay; }
        @Override public void draw(Batch batch,float x,float y,float w,float h) { base.draw(batch,x,y,w,h); overlay.draw(batch,x,y,w,h); }
    }
    private static final class Dropdown extends BaseDrawable {
        final Drawable background,arrow,divider;
        Dropdown(Drawable background,Drawable arrow,Drawable divider) {
            super(background); this.background=background; this.arrow=arrow; this.divider=divider;
            setLeftWidth(16); setRightWidth(52); setTopHeight(9); setBottomHeight(9);
        }
        @Override public void draw(Batch batch,float x,float y,float w,float h) {
            background.draw(batch,x,y,w,h);
            divider.draw(batch,x+w-44,y+9,1,h-18);
            for(int i=0;i<6;i++) arrow.draw(batch,x+w-29+i,y+h/2+4-i,12-i*2,2);
        }
    }
}

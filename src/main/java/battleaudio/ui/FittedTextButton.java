package battleaudio.ui;

import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;

/** Fitting text never changes the preferred size used by the parent table. */
final class FittedTextButton extends TextButton {
    private final float baseScaleX,baseScaleY;
    private String measuredText;
    private float naturalWidth,naturalHeight;
    private boolean initialized;
    FittedTextButton(String text,Skin skin,String style) {
        super(text,skin,style);
        baseScaleX=getStyle().font.getData().scaleX;baseScaleY=getStyle().font.getData().scaleY;
        initialized=true;measure();getLabelCell().minWidth(0);
    }
    private void measure() {
        String text=getText().toString();if(text.equals(measuredText))return;
        BitmapFont font=getStyle().font;float previousX=font.getData().scaleX,previousY=font.getData().scaleY;
        try {
            font.getData().setScale(baseScaleX,baseScaleY);
            GlyphLayout layout=new GlyphLayout(font,text);
            naturalWidth=layout.width;naturalHeight=Math.max(font.getLineHeight(),layout.height);measuredText=text;
        } finally {font.getData().setScale(previousX,previousY);}
    }
    @Override public float getPrefWidth() {
        if(!initialized)return super.getPrefWidth();measure();
        return Math.max(getBackground()==null?0:getBackground().getMinWidth(),naturalWidth+getPadLeft()+getPadRight());
    }
    @Override public float getPrefHeight() {
        if(!initialized)return super.getPrefHeight();measure();
        return Math.max(getBackground()==null?0:getBackground().getMinHeight(),naturalHeight+getPadTop()+getPadBottom());
    }
    @Override public void layout() {
        if(initialized) {
            measure();float available=Math.max(1,getWidth()-getPadLeft()-getPadRight()-8);
            float scale=naturalWidth==0?1:Math.min(1,available/naturalWidth);
            if(Math.abs(getLabel().getFontScaleX()-scale)>.0001f)getLabel().setFontScale(scale);
        }
        super.layout();
    }
}
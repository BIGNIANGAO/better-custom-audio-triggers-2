package battleaudio.ui;

import battleaudio.localization.I18n;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.*;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.utils.Align;
import java.util.*;
import java.util.function.Consumer;

/** Searchable OR selection with a floating list, outside-click dismissal and native check marks. */
final class SearchMultiSelect extends Table {
    private final Skin skin;
    private final String title, name;
    private final java.util.List<FilterOptions.Option> options;
    private final Set<String> selected;
    private final Consumer<Set<String>> changed;
    private final TextField search;
    private final TextButton arrow;
    private final Table popup = new Table();
    private final Table choices = new Table();
    private final ScrollPane scroll;
    private Stage popupStage;
    private ScrollPane anchorScroll;
    private float anchorScrollY;
    private boolean resetting;
    private final InputListener outside = new InputListener() {
        @Override public boolean touchDown(InputEvent event,float x,float y,int pointer,int button) {
            Actor target=event.getTarget();
            if (!target.isDescendantOf(popup) && !target.isDescendantOf(SearchMultiSelect.this)) hide();
            return false;
        }
    };

    SearchMultiSelect(Skin skin,String title,String name,java.util.List<FilterOptions.Option> options,
            Set<String> selected,Consumer<Set<String>> changed) {
        this.skin=skin; this.title=title; this.name=name; this.options=options;
        this.selected=new LinkedHashSet<>(selected); this.changed=changed;
        setName(name+"-chooser");
        SelectBox.SelectBoxStyle selectStyle=skin.get(SelectBox.SelectBoxStyle.class);
        setBackground(selectStyle.background); pad(0);
        TextField.TextFieldStyle searchStyle=new TextField.TextFieldStyle(skin.get(TextField.TextFieldStyle.class));
        searchStyle.background=null; searchStyle.focusedBackground=null; searchStyle.disabledBackground=null;
        search=new TextField("",searchStyle); search.setName(name+"-search"); search.setProgrammaticChangeEvents(true);
        TextButton.TextButtonStyle arrowStyle=new TextButton.TextButtonStyle();
        arrowStyle.font=skin.getFont("settings-font"); arrowStyle.fontColor=com.megacrit.cardcrawl.core.Settings.GOLD_COLOR;
        arrowStyle.overFontColor=com.megacrit.cardcrawl.core.Settings.GOLD_COLOR;
        arrowStyle.downFontColor=com.badlogic.gdx.graphics.Color.WHITE;
        arrow=new TextButton("",arrowStyle); arrow.setName(name+"-toggle");
        arrow.setProgrammaticChangeEvents(false);
        add(search).minWidth(0).grow().padLeft(16).padRight(8); add(arrow).width(44).growY();
        popup.setName(name+"-popup"); popup.top().left();
        popup.setBackground(skin.get(com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle.class).background); popup.pad(8);
        choices.top().left(); scroll=new ScrollPane(choices,skin); scroll.setName(name+"-list");
        scroll.setFadeScrollBars(false); scroll.setScrollingDisabled(true,false);
        scroll.setOverscroll(false,false); scroll.setFlickScroll(false); scroll.setVariableSizeKnobs(false);
        popup.add(scroll).minWidth(0).grow();
        search.addListener(new InputListener(){
            @Override public boolean touchDown(InputEvent e,float x,float y,int p,int b){show();return false;}
        });
        search.addListener(new ChangeListener(){public void changed(ChangeEvent e,Actor a){
            if(resetting)return; show(); refresh(); scroll.setScrollY(0);
        }});
        arrow.addListener(new ChangeListener(){public void changed(ChangeEvent e,Actor a){
            arrow.setChecked(false); if(popupStage==null)show();else hide();
        }});
        summary();
    }
    private void summary() {
        java.util.List<String> names=new ArrayList<>();
        for(FilterOptions.Option option:options)if(selected.contains(option.id))names.add(option.name);
        search.getStyle().messageFontColor=names.isEmpty()?skin.get(TextField.TextFieldStyle.class).messageFontColor:search.getStyle().fontColor;
        search.setMessageText(names.isEmpty()?I18n.text("输入名称搜索"):String.join("、",names));
    }
    private void refresh() {
        choices.clear(); int count=0;
        for(FilterOptions.Option option:options)if(option.matches(search.getText())) {
            CheckBox box=new CheckBox(option.name,skin);
            box.setName(name+"-option-"+option.id); box.setProgrammaticChangeEvents(false);
            box.setChecked(selected.contains(option.id));
            box.getLabel().setAlignment(Align.left); box.getLabel().setEllipsis(true);
            box.clearChildren(); box.add(box.getLabel()).minWidth(0).growX().left().padRight(12);
            box.add(box.getImage()).size(32).right();
            box.addListener(new ChangeListener(){public void changed(ChangeEvent e,Actor a){
                if(box.isChecked())selected.add(option.id);else selected.remove(option.id);
                summary(); changed.accept(new LinkedHashSet<>(selected));
            }});
            choices.add(box).minWidth(0).growX().height(48).row(); count++;
        }
        if(count==0)choices.add(new Label(I18n.text("没有匹配项目"),skin)).minWidth(0).growX().height(48).row();
        popup.invalidateHierarchy();
    }
    private void show() {
        if(popupStage!=null)return;
        Stage stage=getStage(); if(stage==null)return;
        dismiss(stage); popupStage=stage;
        Actor ancestor=getParent();
        while(ancestor!=null && !(ancestor instanceof ScrollPane))ancestor=ancestor.getParent();
        anchorScroll=ancestor instanceof ScrollPane?(ScrollPane)ancestor:null;
        anchorScrollY=anchorScroll==null?0:anchorScroll.getScrollY();
        stage.addActor(popup); stage.addCaptureListener(outside);
        refresh(); position();
        if(search.getText().isEmpty() && !selected.isEmpty()) {
            Actor item=choices.findActor(name+"-option-"+selected.iterator().next());
            if(item!=null){scroll.scrollTo(item.getX(),item.getY(),item.getWidth(),item.getHeight());scroll.updateVisualScroll();}
        }
    }
    private void position() {
        if(popupStage==null)return;
        Vector2 point=localToStageCoordinates(new Vector2());
        float worldW=popupStage.getViewport().getWorldWidth(), worldH=popupStage.getViewport().getWorldHeight();
        float width=Math.min(getWidth(),worldW-16);
        float below=Math.max(0,point.y-8), above=Math.max(0,worldH-point.y-getHeight()-8);
        boolean down=below>=180 || below>=above;
        float height=Math.min(320,Math.min(Math.max(64,choices.getPrefHeight()+16),down?below:above));
        popup.setBounds(Math.max(8,Math.min(point.x,worldW-width-8)),
                down?point.y-height:point.y+getHeight(),width,height);
        popup.validate();
    }
    private void hide() {
        if(popupStage==null)return;
        popupStage.removeCaptureListener(outside);
        if(popupStage.getKeyboardFocus()==search)popupStage.setKeyboardFocus(null);
        if(popupStage.getScrollFocus()!=null && popupStage.getScrollFocus().isDescendantOf(popup))popupStage.setScrollFocus(null);
        popup.remove(); popupStage=null; anchorScroll=null;
        resetting=true; search.setText(""); resetting=false;
        summary();
    }
    @Override public void act(float delta) {
        super.act(delta);
        if(popupStage!=null) {
            boolean visible=true;
            for(Actor actor=this;actor!=null;actor=actor.getParent())if(!actor.isVisible()){visible=false;break;}
            if(!visible || anchorScroll!=null && Math.abs(anchorScroll.getScrollY()-anchorScrollY)>.5f)hide();
            else position();
        }
    }
    static boolean dismiss(Stage stage) {
        for(Actor actor:new com.badlogic.gdx.utils.Array<Actor>(stage.getRoot().getChildren())) {
            if(actor.getName()!=null && actor.getName().endsWith("-popup")) {
                // The owner lives under the editor; locate it before removing the detached popup.
                String ownerName=actor.getName().substring(0,actor.getName().length()-6)+"-chooser";
                Actor owner=stage.getRoot().findActor(ownerName);
                if(owner instanceof SearchMultiSelect)((SearchMultiSelect)owner).hide();
                else actor.remove();
                return true;
            }
        }
        return false;
    }
}

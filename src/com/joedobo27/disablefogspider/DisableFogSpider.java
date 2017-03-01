package com.joedobo27.disablefogspider;


import com.wurmonline.server.bodys.Wound;
import com.wurmonline.server.creatures.*;
import com.wurmonline.server.skills.Skill;
import com.wurmonline.server.skills.SkillList;
import com.wurmonline.server.skills.Skills;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.NotFoundException;
import javassist.expr.ExprEditor;
import javassist.expr.MethodCall;
import org.gotti.wurmunlimited.modloader.ReflectionUtil;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.Initable;
import org.gotti.wurmunlimited.modloader.interfaces.ServerStartedListener;
import org.gotti.wurmunlimited.modloader.interfaces.WurmServerMod;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DisableFogSpider implements WurmServerMod, Initable, ServerStartedListener, Configurable {

    private static ClassPool classPool = HookManager.getInstance().getClassPool();
    private static Logger logger = Logger.getLogger(DisableFogSpider.class.getName());
    private boolean fogNoSpawnFogSpiders = false;
    private boolean fogSpiderLikeGiantSpider = false;

    @Override
    public void configure(Properties properties) {
        fogNoSpawnFogSpiders = Boolean.parseBoolean(properties.getProperty("fogNoSpawnFogSpiders", Boolean.toString(fogNoSpawnFogSpiders)));
        fogSpiderLikeGiantSpider = Boolean.parseBoolean(properties.getProperty("fogSpiderLikeGiantSpider", Boolean.toString(fogSpiderLikeGiantSpider)));
    }

    @Override
    public void init() {
        try {
            disableFogSpiderInPoll();
        } catch (NotFoundException | CannotCompileException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
    }

    @Override
    public void onServerStarted() {
        JAssistClassData.voidClazz();
        weakenFogSpider();
    }

    /**
     * Change Zone.poll() to disable the creation of Fog spiders.
     * Use ExprEditor to change getFog() so it always returns Float.MIN_VALUE and thus disables creation.
     * was--
     *      if (this.isOnSurface()) {
     *          if (Server.getWeather().getFog() > 0.5f && Zone.fogSpiders.size() < Zones.worldTileSizeX / 10) {
     *
     * Edit was on bytecode index 322 which goes with line number 534.
     *
     * @throws NotFoundException JA related, forwarded.
     * @throws CannotCompileException JA related, forwarded.
     */
    private void disableFogSpiderInPoll() throws NotFoundException, CannotCompileException {
        if (!fogNoSpawnFogSpiders)
            return;
        final int[] successes = new int[]{0};
        JAssistClassData forage = new JAssistClassData("com.wurmonline.server.zones.Zone", classPool);
        JAssistMethodData poll =  new JAssistMethodData(forage, "(I)V", "poll");

        poll.getCtMethod().instrument(new ExprEditor() {
            @Override
            public void edit(MethodCall methodCall) throws CannotCompileException {
                if (Objects.equals("getFog", methodCall.getMethodName()) && methodCall.getLineNumber() == 534){
                    logger.log(Level.FINE, "Zone.class, poll(), installed hook at line: " + methodCall.getLineNumber());
                    methodCall.replace("$_ = Float.MIN_VALUE;");
                    successes[0] = 1;
                }
            }
        });
        evaluateChangesArray(successes, "fogNoSpawnFogSpiders");
    }

    /**
     * Method to make a fog spider about the same strength as a giant spider. This alters weaponless fighting, bite damage,
     * base combat rating, damage type.
     */
    private void weakenFogSpider() {
        if (!fogSpiderLikeGiantSpider)
            return;
        int[] successes = new int[]{0,0,0,0};
        CreatureTemplate fogSpiderTemplate = getFogSpiderTemplate();
        if (fogSpiderTemplate == null)
            return;

        try {
            Skills fogSpiderSkills = getFogSpiderSkills(fogSpiderTemplate);
            Skill weaponlessFighting = getSkillInSkills(fogSpiderSkills, SkillList.WEAPONLESS_FIGHTING);
            if (weaponlessFighting != null) {
                ReflectionUtil.setPrivateField(weaponlessFighting, ReflectionUtil.getField(Class.forName("com.wurmonline.server.skills.Skill"),
                        "knowledge"), 40.0f);
                logger.log(Level.FINE, "Set fog spider's weaponless fighting to 40.");
                successes[0] = 1;
            }

            ReflectionUtil.setPrivateField(fogSpiderTemplate, ReflectionUtil.getField(Class.forName("com.wurmonline.server.creatures.CreatureTemplate"),
                    "biteDamage"), 6.0f);
            logger.log(Level.FINE, "Set fog spider's bite damage to 6.");
            successes[1] = 1;
        }catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException ignored) {}

        fogSpiderTemplate.setBaseCombatRating(10.0f);
        logger.log(Level.FINE, "Set fog spider's base combat rating to 10.");
        successes[2] = 1;

        fogSpiderTemplate.setCombatDamageType(Wound.TYPE_PIERCE);
        logger.log(Level.FINE, "Set fog spider's damage type to pierce.");
        successes[3] = 1;

        evaluateChangesArray(successes, "fogSpiderLikeGiantSpider");
    }

    /**
     * Reflective wrapper to fetch the package local Skills field contained in the skills arg.
     *
     * @param skills Skills, WU Object
     * @param skill int object. This is an identifier int for a skill, References in WU SkillList.class.
     */
    private Skill getSkillInSkills(Skills skills, int skill) {
        try {
            Map<Integer, Skill> aSkills = ReflectionUtil.getPrivateField(skills, ReflectionUtil.getField(Class.forName(
                    "com.wurmonline.server.skills.Skills"), "skills"));
            for (Map.Entry<Integer, Skill> entry : aSkills.entrySet()) {
                if (entry.getKey() == skill) {
                    return entry.getValue();
                }
            }
        } catch (ClassNotFoundException | NoSuchFieldException | IllegalAccessException e) {
            logger.log(Level.WARNING, e.getMessage(), e);
        }
        return null;
    }

    /**
     * Wrapper to ignore a useless WU exception.
     *
     * @return CreatureTemplate, WU object
     */
    private CreatureTemplate getFogSpiderTemplate() {
        try {
            return CreatureTemplateFactory.getInstance().getTemplate(Creatures.SPIDER_FOG_CID);
        } catch (NoSuchCreatureTemplateException ignored){}
        return null;
    }

    /**
     * Wrapper to ignore a useless WU exception.
     *
     * @param fogSpiderTemplate CreatureTemplate, WU object
     * @return Skills, WU object
     */
    private Skills getFogSpiderSkills(CreatureTemplate fogSpiderTemplate) {
        try {
            return fogSpiderTemplate.getSkills();
        }catch (Exception ignored){}
        return null;
    }

    /**
     * Used to standardize processing results from changes. All ints must be 1 to succeed.
     *
     * @param ints int[] object. Each array entry represent a change.
     * @param option String object, the name of configure option.
     */
    private static void evaluateChangesArray(int[] ints, String option) {
        boolean changesSuccessful = Arrays.stream(ints).noneMatch(value -> value == 0);
        if (changesSuccessful) {
            logger.log(Level.INFO, option + " option changes SUCCESSFUL");
        } else {
            logger.log(Level.INFO, option + " option changes FAILURE");
            logger.log(Level.FINE, Arrays.toString(ints));
        }
    }
}

package battleaudio.storage;

import battleaudio.model.Config;
import battleaudio.model.Config.Rule;
import battleaudio.model.MusicPack;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pure package-level edits; file reclamation is deliberately delegated to AudioLibrary. */
public final class MusicPackages {
    private static final Gson GSON = new Gson();
    private MusicPackages() { }

    public static Config copyAsLocal(Config current, String sourcePackId) {
        return copyAsLocal(current, sourcePackId, " 副本");
    }

    public static Config copyAsLocal(Config current, String sourcePackId, String copySuffix) {
        Config next = current.copy();
        MusicPack source = requirePack(next, sourcePackId);
        MusicPack copy = GSON.fromJson(GSON.toJson(source), MusicPack.class);
        copy.id = UUID.randomUUID().toString();
        copy.contentId = UUID.randomUUID().toString();
        copy.name = uniqueName(source.name + (copySuffix == null || copySuffix.trim().isEmpty() ? " 副本" : copySuffix), next.packs);
        copy.source = MusicPack.Source.LOCAL;
        copy.workshopId = "";
        copy.version = "1";
        copy.ruleIds = new ArrayList<>();
        copy.ownedFiles = new ArrayList<>();

        Set<String> sourceRuleIds = new HashSet<>();
        for (Rule rule : next.rules) if (source.id.equals(rule.packId)) sourceRuleIds.add(rule.id);
        for (Rule rule : next.rules) if (source.id.equals(rule.packId)) {
            for (String related : rule.pauseWhenRuleIds) if (!sourceRuleIds.contains(related))
                throw new IllegalArgumentException("音乐包包含指向包外的暂停关联，先解除关联再复制");
            for (String related : rule.prerequisiteRuleIds) if (!sourceRuleIds.contains(related))
                throw new IllegalArgumentException("音乐包包含指向包外的前置关联，先解除关联再复制");
        }
        Map<String, String> ruleIds = new HashMap<>();
        List<Rule> copiedRules = new ArrayList<>();
        for (Rule rule : next.rules) if (source.id.equals(rule.packId)) {
            Rule cloned = GSON.fromJson(GSON.toJson(rule), Rule.class);
            String id = stableRuleId(copy.id, rule.id);
            ruleIds.put(rule.id, id);
            cloned.id = id;
            cloned.packId = copy.id;
            cloned.upstreamRuleId = null;
            for (Config.Candidate candidate : cloned.candidates) candidate.id = stableRuleId(id, candidate.id);
            copiedRules.add(cloned);
        }
        for (Rule rule : copiedRules) {
            remap(rule.pauseWhenRuleIds, ruleIds);
            remap(rule.prerequisiteRuleIds, ruleIds);
        }
        next.rules.addAll(copiedRules);
        next.packs.add(copy);
        next.refreshPackMembership();
        next.validate();
        return next;
    }

    /** Replaces all rules for a subscribed package; there is intentionally no three-way merge. */
    public static Config applySubscription(Config current, String workshopId, String contentId, String name,
            String version, List<Rule> incomingRules) {
        if (workshopId == null || !workshopId.matches("[0-9]{1,20}"))
            throw new IllegalArgumentException("创意工坊音乐包 ID 无效");
        if (contentId == null || !contentId.matches("[A-Za-z0-9._-]{1,128}") || contentId.contains(".."))
            throw new IllegalArgumentException("音乐包内容 ID 无效");
        if (incomingRules == null) throw new IllegalArgumentException("音乐包规则缺失");
        Config next = current.copy();
        MusicPack target = findSubscription(next, workshopId);
        if (target == null) {
            target = new MusicPack();
            target.id = "workshop-" + workshopId;
            if (next.pack(target.id) != null) target.id = UUID.randomUUID().toString();
            target.source = MusicPack.Source.SUBSCRIBED;
            target.workshopId = workshopId;
            target.contentId = contentId;
            target.enabled = true;
            next.packs.add(target);
        } else if (target.source != MusicPack.Source.SUBSCRIBED) {
            throw new IllegalArgumentException("此工坊项目已被本地音乐包占用，原内容未更改");
        } else if (target.contentId != null && !target.contentId.equals(contentId)) {
            throw new IllegalArgumentException("音乐包内容 ID 与已安装项目不一致，旧版本已保留");
        }
        target.contentId = contentId;
        target.name = name == null || name.trim().isEmpty() ? "未命名音乐包" : name.trim();
        target.version = version == null || version.trim().isEmpty() ? "unknown" : version.trim();
        target.ownedFiles = new ArrayList<>();

        List<Rule> previous = new ArrayList<>();
        Map<String, Boolean> enabledBySource = new HashMap<>();
        for (Rule rule : next.rules) if (target.id.equals(rule.packId)) {
            previous.add(rule);
            if (rule.upstreamRuleId != null) enabledBySource.put(rule.upstreamRuleId, rule.enabled);
        }
        next.rules.removeAll(previous);

        Set<String> incomingSourceIds = new HashSet<>();
        for (Rule source : incomingRules) if (source != null && source.id != null) incomingSourceIds.add(source.id);
        for (Rule source : incomingRules) {
            if (source == null) continue;
            for (String related : source.pauseWhenRuleIds) if (!incomingSourceIds.contains(related))
                throw new IllegalArgumentException("工坊音乐包不能依赖包外暂停规则");
            for (String related : source.prerequisiteRuleIds) if (!incomingSourceIds.contains(related))
                throw new IllegalArgumentException("工坊音乐包不能依赖包外前置规则");
        }
        Map<String, String> incomingIds = new HashMap<>();
        for (Rule source : incomingRules) {
            if (source == null || source.id == null || source.id.trim().isEmpty())
                throw new IllegalArgumentException("更新中包含无效规则，旧版本已保留");
            if (incomingIds.put(source.id, stableRuleId(target.id, source.id)) != null)
                throw new IllegalArgumentException("更新中包含重复规则 ID，旧版本已保留");
        }
        Set<String> usedIds = new HashSet<>();
        for (Rule existing : next.rules) usedIds.add(existing.id);
        for (String newId : incomingIds.values()) if (!usedIds.add(newId))
            throw new IllegalArgumentException("更新中的规则 ID 与其他音乐包冲突，旧版本已保留");
        for (Rule source : incomingRules) {
            Rule installed = GSON.fromJson(GSON.toJson(source), Rule.class);
            installed.id = incomingIds.get(source.id);
            installed.packId = target.id;
            installed.upstreamRuleId = source.id;
            installed.enabled = enabledBySource.containsKey(source.id) ? enabledBySource.get(source.id) : source.enabled;
            remap(installed.pauseWhenRuleIds, incomingIds);
            remap(installed.prerequisiteRuleIds, incomingIds);
            next.rules.add(installed);
        }
        Set<String> retained = new HashSet<>();
        for (Rule rule : next.rules) retained.add(rule.id);
        for (Rule rule : next.rules) {
            rule.pauseWhenRuleIds.removeIf(id -> !retained.contains(id));
            rule.prerequisiteRuleIds.removeIf(id -> !retained.contains(id));
        }
        next.refreshPackMembership();
        next.validate();
        return next;
    }

    /** Removes only the matching subscribed item. Local copies are never matched by Workshop ID. */
    public static Config unsubscribe(Config current, String workshopId) {
        Config next = current.copy();
        MusicPack target = findSubscription(next, workshopId);
        if (target == null) return next;
        Set<String> removedIds = new HashSet<>();
        for (Rule rule : next.rules) if (target.id.equals(rule.packId)) removedIds.add(rule.id);
        next.rules.removeIf(rule -> target.id.equals(rule.packId));
        next.packs.remove(target);
        for (Rule rule : next.rules) {
            rule.pauseWhenRuleIds.removeIf(removedIds::contains);
            rule.prerequisiteRuleIds.removeIf(removedIds::contains);
        }
        next.refreshPackMembership();
        next.validate();
        return next;
    }

    private static MusicPack findSubscription(Config config, String workshopId) {
        if (workshopId == null) return null;
        for (MusicPack pack : config.packs)
            if (pack.source == MusicPack.Source.SUBSCRIBED && workshopId.equals(pack.workshopId)) return pack;
        return null;
    }

    private static MusicPack requirePack(Config config, String packId) {
        MusicPack pack = config.pack(packId);
        if (pack == null) throw new IllegalArgumentException("音乐包不存在");
        return pack;
    }

    private static String uniqueName(String base, List<MusicPack> packs) {
        Set<String> names = new HashSet<>();
        for (MusicPack pack : packs) names.add(pack.name);
        if (!names.contains(base)) return base;
        for (int index = 2; ; index++) if (!names.contains(base + " (" + index + ")")) return base + " (" + index + ")";
    }

    public static String stableRuleId(String packId, String sourceId) {
        return UUID.nameUUIDFromBytes((packId + "/" + sourceId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void remap(List<String> ids, Map<String, String> mapping) {
        if (ids == null) return;
        for (int index = 0; index < ids.size(); index++) {
            String replacement = mapping.get(ids.get(index));
            if (replacement != null) ids.set(index, replacement);
        }
    }
}

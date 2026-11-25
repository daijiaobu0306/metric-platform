package com.metric.alarm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.alarm.entity.AlarmAuditLog;
import com.metric.alarm.entity.AlarmStrategy;
import com.metric.alarm.entity.StrategyRule;
import com.metric.alarm.repository.AlarmStrategyRepository;
import com.metric.metric.entity.MetricCategory;
import com.metric.metric.entity.MetricItem;
import com.metric.metric.entity.SystemInfo;
import com.metric.metric.service.MetricService;
import com.metric.metric.service.MetricSubCategoryService;
import com.metric.metric.service.SystemService;
import com.metric.security.service.CurrentUserProvider;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 从完整对象目录导出六档工作表，预览后按策略原地合并，不删除缺席/空白档次。 */
@Service
public class StrategyExcelService {
    private static final String[] SHEETS = {"告警-一般", "告警-重要", "告警-严重", "工单-一般", "工单-重要", "工单-严重"};
    private static final String[] HEADERS = {"对象名称", "策略ID", "策略类型", "对象类型", "对象ID", "系统编码", "系统名称",
            "指标大类编码", "指标大类名称", "指标小类编码", "指标小类名称", "指标编码", "指标名称", "单位",
            "策略名称", "比较条件", "阈值", "连续有效更新次数", "规则开关", "策略状态", "策略更新时间"};
    private static final long TTL = 15 * 60 * 1000L;
    private static final int MAX_ROWS = 100000;
    private final Map<String, CachedPlan> plans = new ConcurrentHashMap<>();
    @Autowired private AlarmStrategyRepository strategies;
    @Autowired private StrategyRuleService ruleService;
    @Autowired private MetricService metrics;
    @Autowired private SystemService systems;
    @Autowired private MetricSubCategoryService subcategories;
    @Autowired private AlarmAuditService audit;
    @Autowired private ObjectMapper mapper;
    @Autowired private RawStrategyExcel rawExcel;

    private record Target(String type, Long id, String systemCode, String systemName, String categoryCode,
                          String categoryName, String subCode, String subName, String metricCode,
                          String metricName, String unit) {
        String key() { return type + ":" + id; }
        String name() { return switch (type) {
            case "system" -> systemName; case "category" -> categoryName;
            case "subCategory" -> subName; default -> metricName;
        }; }
        String codeKey() { return String.join("|", type, systemCode, categoryCode, subCode, metricCode); }
    }
    private static class Mutation {
        String key;
        Target target;
        AlarmStrategy before;
        AlarmStrategy after;
        String name;
        Integer status;
        final Map<Boolean, Integer> enabled = new HashMap<>();
    }
    private record CachedPlan(String owner, long created, List<Mutation> mutations, Map<String, String> versions, RawStrategyExcel.Plan raw) {}
    public record ImportError(String sheet, int row, String message) {}
    public record RowChange(String sheet, int row, String target, String strategyName, String action, String before, String after) {}

    @Transactional(readOnly = true)
    public byte[] exportWorkbook() throws IOException {
        List<Target> catalog = catalog();
        Map<String, List<AlarmStrategy>> groups = groups(strategies.findAll());
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle identity = workbook.createCellStyle();
            identity.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            identity.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle input = workbook.createCellStyle();
            input.setFillForegroundColor(IndexedColors.LIGHT_YELLOW.getIndex());
            input.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            CellStyle header = workbook.createCellStyle();
            header.setFillForegroundColor(IndexedColors.PALE_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setWrapText(true);
            Font normal = workbook.createFont(); normal.setBold(false); header.setFont(normal);
            CellStyle number = workbook.createCellStyle(); number.cloneStyleFrom(input);
            number.setDataFormat(workbook.createDataFormat().getFormat("0.####"));
            for (int sheetIndex = 0; sheetIndex < SHEETS.length; sheetIndex++) {
                boolean ticket = sheetIndex >= 3;
                int level = sheetIndex % 3 + 2;
                Sheet sheet = workbook.createSheet(SHEETS[sheetIndex]);
                Row headers = sheet.createRow(0); headers.setHeightInPoints(30);
                for (int col = 0; col < HEADERS.length; col++) {
                    Cell cell = headers.createCell(col); cell.setCellValue(HEADERS[col]); cell.setCellStyle(header);
                    if (col == 17) {
                        var helper = workbook.getCreationHelper();
                        var anchor = helper.createClientAnchor(); anchor.setCol1(col); anchor.setCol2(col + 4); anchor.setRow1(0); anchor.setRow2(5);
                        var comment = sheet.createDrawingPatriarch().createCellComment(anchor);
                        comment.setAuthor("指标体系平台");
                        comment.setString(helper.createRichTextString("每个新的数据时点计1次；同一时点重传、修订和重复扫描不累计。每小时更新的指标填3，表示连续3份新数据异常；不是连续3次扫描。"));
                        cell.setCellComment(comment);
                    }
                    sheet.setColumnWidth(col, (col == 14 ? 42 : col == 20 ? 29 : col % 2 == 0 ? 19 : 24) * 256);
                }
                int rowIndex = 1;
                for (Target target : catalog) {
                    for (String type : "metric".equals(target.type()) ? List.of("metric", "health") : List.of("health")) {
                        List<AlarmStrategy> found = groups.getOrDefault(groupKey(target, type), List.of());
                        List<AlarmStrategy> rows = found.isEmpty() ? Collections.singletonList(null) : found;
                        for (AlarmStrategy strategy : rows) {
                            StrategyRule rule = strategy == null ? null : configuredRules(strategy, ticket).stream()
                                    .filter(item -> Objects.equals(item.getLevel(), level)).findFirst().orElse(null);
                            Row row = sheet.createRow(rowIndex++);
                            Object[] values = {target.name(), strategy == null ? "" : strategy.getId().toString(), typeLabel(type), targetLabel(target.type()),
                                    target.id().toString(), target.systemCode(), target.systemName(), target.categoryCode(), target.categoryName(),
                                    target.subCode(), target.subName(), target.metricCode(), target.metricName(), "health".equals(type) ? "分" : target.unit(),
                                    strategy == null ? defaultName(target, type) : strategy.getStrategyName(), rule == null ? "" : rule.getCondition(),
                                    rule == null ? null : rule.getThreshold(), rule == null ? 1 : rule.getConsecutiveTimes(),
                                    enabledText(strategy == null ? 1 : ticket ? strategy.getGongdanEnabled() : strategy.getAlarmEnabled()),
                                    enabledText(strategy == null ? 1 : strategy.getStatus()),
                                    strategy == null || strategy.getUpdateTime() == null ? "" : strategy.getUpdateTime().toString()};
                            for (int col = 0; col < values.length; col++) {
                                Cell cell = row.createCell(col);
                                cell.setCellStyle(col >= 14 && col <= 19 ? input : identity);
                                if (values[col] instanceof BigDecimal decimal) {
                                    // Excel支持15位有效数字；本字段最多11位整数+4位小数。
                                    cell.setCellValue(decimal.doubleValue()); cell.setCellStyle(number);
                                } else if (values[col] instanceof Integer integer) cell.setCellValue(integer);
                                else if (values[col] != null) cell.setCellValue(values[col].toString());
                            }
                        }
                    }
                }
                sheet.createFreezePane(1, 1);
                sheet.setColumnWidth(0, 30 * 256);
                // 主键、版本与辅助编码保留在文件中但默认隐藏，减少日常填值的横向负担。
                for (int hidden : new int[]{1, 4, 5, 7, 9, 12, 20}) sheet.setColumnHidden(hidden, true);
                sheet.setAutoFilter(new CellRangeAddress(0, Math.max(0, rowIndex - 1), 0, HEADERS.length - 1));
                addValidation(sheet, 15, new String[]{">", ">=", "<", "<=", "="});
                addValidation(sheet, 18, new String[]{"启用", "停用"});
                addValidation(sheet, 19, new String[]{"启用", "停用"});
                sheet.setZoom(85);
            }
            rawExcel.append(workbook);
            workbook.write(out); return out.toByteArray();
        }
    }

    private void addValidation(Sheet sheet, int col, String[] options) {
        DataValidationHelper helper = sheet.getDataValidationHelper();
        DataValidation validation = helper.createValidation(helper.createExplicitListConstraint(options),
                new CellRangeAddressList(1, 1048575, col, col));
        validation.setShowErrorBox(true); validation.setEmptyCellAllowed(true);
        validation.createErrorBox("请选择列表中的值", String.join("、", options));
        sheet.addValidationData(validation);
    }

    @Transactional(readOnly = true)
    public synchronized Map<String, Object> preview(byte[] bytes, String owner) throws IOException {
        plans.entrySet().removeIf(entry -> System.currentTimeMillis() - entry.getValue().created() > TTL || Objects.equals(entry.getValue().owner(), owner));
        if (bytes.length > 20 * 1024 * 1024) throw new IllegalArgumentException("文件不能超过20MB");
        List<Target> catalog = catalog();
        Map<String, Target> byId = new HashMap<>();
        Map<String, List<Target>> byCode = new HashMap<>();
        for (Target target : catalog) {
            byId.put(target.key(), target);
            byCode.computeIfAbsent(target.codeKey(), ignored -> new ArrayList<>()).add(target);
        }
        List<AlarmStrategy> all = strategies.findAll();
        Map<Long, AlarmStrategy> byStrategy = new HashMap<>(); all.forEach(s -> byStrategy.put(s.getId(), s));
        Map<String, List<AlarmStrategy>> groups = groups(all);
        Map<String, Mutation> mutations = new LinkedHashMap<>();
        Set<String> slots = new HashSet<>();
        List<ImportError> errors = new ArrayList<>();
        List<RowChange> changes = new ArrayList<>();
        Map<RowChange, String> changeKeys = new HashMap<>();
        int skipped = 0, total = 0;
        RawStrategyExcel.Plan rawPlan=RawStrategyExcel.Plan.empty();
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets()!=6 && !(workbook.getNumberOfSheets()==7 && workbook.getSheet(RawStrategyExcel.SHEET)!=null)) throw new IllegalArgumentException("模板须包含六个分级sheet，可附带原始数据策略sheet，请重新导出模板");
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for (int sheetIndex = 0; sheetIndex < SHEETS.length; sheetIndex++) {
                Sheet sheet = workbook.getSheet(SHEETS[sheetIndex]);
                if (sheet == null) throw new IllegalArgumentException("缺少sheet：" + SHEETS[sheetIndex]);
                // 新版首行表头；兼容此前下载的两行说明版。
                int headerIndex = HEADERS[0].equals(text(sheet.getRow(0), 0, evaluator)) ? 0 : 2;
                for (int col = 0; col < HEADERS.length; col++) {
                    if (!HEADERS[col].equals(text(sheet.getRow(headerIndex), col, evaluator))
                            && !(col == 17 && "连续次数".equals(text(sheet.getRow(headerIndex), col, evaluator))))
                        throw new IllegalArgumentException(sheet.getSheetName() + "表头不匹配：" + HEADERS[col]);
                }
                boolean ticket = sheetIndex >= 3;
                int level = sheetIndex % 3 + 2;
                for (int rowIndex = headerIndex + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    if (++total > MAX_ROWS) throw new IllegalArgumentException("文件数据行不能超过" + MAX_ROWS);
                    try {
                        String thresholdText = text(row, 16, evaluator);
                        if (thresholdText.isBlank()) { skipped++; continue; }
                        BigDecimal threshold = new BigDecimal(thresholdText).stripTrailingZeros();
                        if (threshold.scale() > 4 || threshold.abs().compareTo(new BigDecimal("100000000000")) >= 0)
                            throw new IllegalArgumentException("阈值最多11位整数、4位小数");
                        String type = parseType(text(row, 2, evaluator));
                        String targetType = parseTargetType(text(row, 3, evaluator));
                        if ("metric".equals(type) && !"metric".equals(targetType)) throw new IllegalArgumentException("指标值策略只能关联具体指标");
                        Target supplied = new Target(targetType, optionalId(text(row, 4, evaluator)), text(row, 5, evaluator), text(row, 6, evaluator),
                                text(row, 7, evaluator), text(row, 8, evaluator), text(row, 9, evaluator), text(row, 10, evaluator),
                                text(row, 11, evaluator), text(row, 12, evaluator), text(row, 13, evaluator));
                        requireCodes(supplied);
                        Target target;
                        if (supplied.id() != null) target = byId.get(supplied.key());
                        else {
                            List<Target> matching = byCode.getOrDefault(supplied.codeKey(), List.of());
                            if (matching.size() != 1) throw new IllegalArgumentException("对象编码无法唯一匹配，请保留导出的对象ID和编码");
                            target = matching.get(0);
                        }
                        if (target == null) throw new IllegalArgumentException("对象ID不存在或对象已被删除，请重新导出");
                        verifyIdentity(supplied, target, type);
                        if (!text(row, 0, evaluator).equals(target.name())) throw new IllegalArgumentException("对象名称不一致，请勿修改身份列");
                        Long strategyId = optionalId(text(row, 1, evaluator));
                        List<AlarmStrategy> candidates = groups.getOrDefault(groupKey(target, type), List.of());
                        AlarmStrategy existing = strategyId == null ? null : byStrategy.get(strategyId);
                        if (strategyId != null && (existing == null || !groupKey(target, type).equals(groupKey(existing))))
                            throw new IllegalArgumentException("策略ID与策略类型/对象不匹配");
                        if (strategyId == null) {
                            if (candidates.size() > 1) throw new IllegalArgumentException("该对象存在多条策略，请填写导出的策略ID");
                            if (!candidates.isEmpty()) existing = candidates.get(0);
                        }
                        if (strategyId != null && !Objects.equals(text(row, 20, evaluator), value(existing.getUpdateTime())))
                            throw new IllegalArgumentException("策略已在导出后变更，请重新导出，避免覆盖他人修改");
                        String key = existing == null ? "new:" + groupKey(target, type) : "id:" + existing.getId();
                        if (!slots.add(key + ":" + sheetIndex)) throw new IllegalArgumentException("同一策略、生成类型、级别在文件中重复");
                        Mutation mutation = mutations.get(key);
                        if (mutation == null) {
                            mutation = new Mutation(); mutation.key = key; mutation.target = target; mutation.before = existing;
                            mutation.after = copy(existing == null ? newStrategy(target, type) : existing);
                            // 脚本改库可能写入旧别名/中文类型；回写时统一为标准值，后续列表与导出不再靠推断。
                            mutation.after.setStrategyType(type);
                            // 两类规则都从当前值复制，避免新增一档时丢掉另一类或其他档次。
                            if (mutation.after.getAlarmRules() != null) mutation.after.setAlarmRules(copyRules(mutation.after.getAlarmRules()));
                            if (mutation.after.getGongdanRules() != null) mutation.after.setGongdanRules(copyRules(mutation.after.getGongdanRules()));
                            mutations.put(key, mutation);
                        }
                        String name = text(row, 14, evaluator);
                        if (name.isBlank()) name = existing == null ? defaultName(target, type) : existing.getStrategyName();
                        if (name.length() > 100) throw new IllegalArgumentException("策略名称不能超过100字");
                        int status = parseEnabled(text(row, 19, evaluator));
                        int enabled = parseEnabled(text(row, 18, evaluator));
                        if (mutation.name != null && !mutation.name.equals(name)) throw new IllegalArgumentException("同一策略在不同sheet的策略名称不一致");
                        if (mutation.status != null && mutation.status != status) throw new IllegalArgumentException("同一策略在不同sheet的策略状态不一致");
                        if (mutation.enabled.containsKey(ticket) && mutation.enabled.get(ticket) != enabled)
                            throw new IllegalArgumentException("同一策略同类规则开关在不同sheet中不一致");
                        String condition = text(row, 15, evaluator);
                        if (!Set.of(">", ">=", "<", "<=", "=").contains(condition)) throw new IllegalArgumentException("比较条件未配置或无效，请从下拉列表选择");
                        int times = new BigDecimal(text(row, 17, evaluator)).intValueExact();
                        if (times < 1) throw new IllegalArgumentException("连续有效更新次数必须是正整数");
                        StrategyRule rule = new StrategyRule(); rule.setLevel(level); rule.setCondition(condition);
                        rule.setThreshold(threshold); rule.setConsecutiveTimes(times);
                        if (ticket && mutation.after.getGongdanRules() == null)
                            mutation.after.setGongdanRules(copyRules(configuredRules(mutation.after, true)));
                        if (!ticket && mutation.after.getAlarmRules() == null)
                            mutation.after.setAlarmRules(copyRules(configuredRules(mutation.after, false)));
                        List<StrategyRule> rules = ticket ? mutation.after.getGongdanRules() : mutation.after.getAlarmRules();
                        StrategyRule old = rules.stream().filter(item -> Objects.equals(item.getLevel(), level)).findFirst().orElse(null);
                        String before = (old == null ? "未配置" : describe(old))
                                + (existing == null ? "" : "；规则" + enabledText(ticket ? existing.getGongdanEnabled() : existing.getAlarmEnabled()) + "；策略" + enabledText(existing.getStatus()));
                        rules.removeIf(item -> Objects.equals(item.getLevel(), level)); rules.add(rule);
                        mutation.name = name; mutation.status = status; mutation.enabled.put(ticket, enabled);
                        mutation.after.setStrategyName(name); mutation.after.setStatus(status);
                        if (ticket) mutation.after.setGongdanEnabled(enabled); else mutation.after.setAlarmEnabled(enabled);
                        RowChange change = new RowChange(sheet.getSheetName(), rowIndex + 1, target.name(), name,
                                old == null ? "新增档次" : "设置档次", before, describe(rule) + "；规则" + enabledText(enabled) + "；策略" + enabledText(status));
                        changes.add(change); changeKeys.put(change, key);
                    } catch (RuntimeException error) {
                        errors.add(new ImportError(sheet.getSheetName(), rowIndex + 1, error instanceof NumberFormatException || error instanceof ArithmeticException
                                ? "阈值须为数值，连续有效更新次数须为正整数；不支持错误公式" : error.getMessage()));
                    }
                }
            }
            if(workbook.getSheet(RawStrategyExcel.SHEET)!=null && total+workbook.getSheet(RawStrategyExcel.SHEET).getLastRowNum()>MAX_ROWS) throw new IllegalArgumentException("文件数据行总数不能超过"+MAX_ROWS);
            rawPlan=rawExcel.preview(workbook);
        }
        List<Mutation> effective = new ArrayList<>();
        for (Mutation mutation : mutations.values()) {
            try {
                ruleService.prepare(mutation.after, null);
                if (mutation.before == null || !state(mutation.before).equals(state(mutation.after))) effective.add(mutation);
            } catch (IllegalArgumentException error) { errors.add(new ImportError("跨sheet校验", 0, mutation.after.getStrategyName() + "：" + error.getMessage())); }
        }
        Set<String> changedKeys = new HashSet<>(); effective.forEach(item -> changedKeys.add(item.key));
        List<RowChange> changedRows = changes.stream().filter(change -> changedKeys.contains(changeKeys.get(change))).toList();
        errors.addAll(rawPlan.errors());
        List<RowChange> allChangedRows=new ArrayList<>(changedRows);allChangedRows.addAll(rawPlan.rows());
        int created = (int) effective.stream().filter(item -> item.before == null).count()+rawPlan.created();
        String token = "";
        if (errors.isEmpty() && (!effective.isEmpty() || !rawPlan.changes().isEmpty())) {
            if (plans.size() >= 20) throw new IllegalArgumentException("待执行导入较多，请稍后重试");
            Map<String, String> versions = new HashMap<>();
            for (Mutation mutation : effective) versions.put(groupKey(mutation.after), version(groups.getOrDefault(groupKey(mutation.after), List.of())));
            token = UUID.randomUUID().toString();
            plans.put(token, new CachedPlan(owner, System.currentTimeMillis(), effective, versions, rawPlan));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token); result.put("newStrategies", created); result.put("updatedStrategies", effective.size()+rawPlan.changes().size() - created);
        result.put("configuredRows", changes.size()+rawPlan.configured()); result.put("skippedRows", skipped+rawPlan.skipped()); result.put("errorCount", errors.size());
        result.put("errors", errors.stream().limit(200).toList()); result.put("rows", allChangedRows.stream().limit(1000).toList());
        result.put("expiresInMinutes", 15);
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized Map<String, Object> execute(String token, String owner) {
        CachedPlan plan = token == null ? null : plans.get(token);
        if (plan == null || System.currentTimeMillis() - plan.created() > TTL || !Objects.equals(owner, plan.owner()))
            throw new IllegalArgumentException("预览已过期或不属于当前用户，请重新校验文件");
        Map<String, Target> currentTargets = new HashMap<>(); catalog().forEach(target -> currentTargets.put(target.key(), target));
        // 先锁定并核对全部目标，再写入；预览后有人修改策略/目录时整批拒绝。
        Set<String> checked = new HashSet<>();
        List<Mutation> ordered = plan.mutations().stream().sorted(Comparator.comparing(item -> item.target.key())).toList();
        for (Mutation mutation : ordered) {
            if (!mutation.target.equals(currentTargets.get(mutation.target.key()))) throw new IllegalArgumentException("对象目录已变化，请重新导出并校验");
            String group = groupKey(mutation.after);
            if (checked.add(group)) {
                List<AlarmStrategy> locked = strategies.lockTargetStrategies(mutation.after.getTargetType(), mutation.after.getTargetId()).stream()
                        .filter(s -> Objects.equals(effectiveType(s), mutation.after.getStrategyType())).toList();
                if (!Objects.equals(plan.versions().get(group), version(locked))) throw new IllegalArgumentException("预览后策略已变化，请重新校验文件");
            }
        }
        rawExcel.check(plan.raw());
        String operator = auditOperator(owner);
        int created = 0;
        for (Mutation mutation : ordered) {
            AlarmStrategy saved = copy(mutation.after);
            ruleService.prepare(saved, null);
            strategies.saveAndFlush(saved);
            AlarmAuditLog log = new AlarmAuditLog(); log.setEntityType(AlarmAuditService.ENTITY_TYPE_ALARM_STRATEGY);
            log.setEntityId(saved.getId()); log.setOperator(operator);
            log.setOperationType(mutation.before == null ? AlarmAuditService.OP_STRATEGY_CREATE : AlarmAuditService.OP_STRATEGY_UPDATE);
            log.setOldValue(mutation.before == null ? null : json(mutation.before)); log.setNewValue(json(saved));
            log.setChangeContent("Excel批量设置告警/工单分级规则：" + saved.getStrategyName());
            audit.log(log);
            if (mutation.before == null) created++;
        }
        List<AlarmStrategy> rawSaved=rawExcel.apply(plan.raw());
        for(int i=0;i<rawSaved.size();i++) {
            AlarmStrategy saved=rawSaved.get(i);RawStrategyExcel.Change change=plan.raw().changes().get(i);
            AlarmAuditLog log=new AlarmAuditLog();log.setEntityType(AlarmAuditService.ENTITY_TYPE_ALARM_STRATEGY);log.setEntityId(saved.getId());log.setOperator(operator);
            log.setOperationType(change.strategy().getId()==null ? AlarmAuditService.OP_STRATEGY_CREATE : AlarmAuditService.OP_STRATEGY_UPDATE);
            log.setOldValue(change.before().isBlank() ? null : change.before());log.setNewValue(json(Map.of("strategy",saved,"definition",change.definition())));
            log.setChangeContent("Excel批量设置原始数据策略："+saved.getStrategyName());audit.log(log);
        }
        created+=plan.raw().created();
        plans.remove(token);
        return Map.of("newStrategies", created, "updatedStrategies", ordered.size()+plan.raw().changes().size() - created, "message", "批量设置成功，未填写的档次保持原样");
    }

    private List<Target> catalog() {
        Map<Long, SystemInfo> systemMap = new HashMap<>(); systems.findAll().forEach(system -> systemMap.put(system.getId(), system));
        Map<Long, MetricCategory> categoryMap = new HashMap<>(); metrics.findAllCategories().forEach(category -> categoryMap.put(category.getId(), category));
        List<Target> result = new ArrayList<>();
        for (SystemInfo system : systemMap.values()) result.add(new Target("system", system.getId(), value(system.getSystemCode()), value(system.getSystemName()), "", "", "", "", "", "", ""));
        for (MetricCategory category : categoryMap.values()) {
            SystemInfo system = systemMap.get(category.getSystemId());
            if (system == null) throw new IllegalArgumentException("指标大类#" + category.getId() + "关联的系统不存在");
            result.add(new Target("category", category.getId(), value(system.getSystemCode()), value(system.getSystemName()), value(category.getCategoryCode()), value(category.getCategoryName()), "", "", "", "", ""));
            for (var sub : subcategories.getByCategoryId(category.getId())) result.add(new Target("subCategory", sub.getId(), value(system.getSystemCode()), value(system.getSystemName()), value(category.getCategoryCode()), value(category.getCategoryName()), value(sub.getSubCategoryCode()), value(sub.getSubCategoryName()), "", "", ""));
        }
        for (MetricItem metric : metrics.findAllItems(Pageable.unpaged()).getContent()) {
            MetricCategory category = categoryMap.get(metric.getCategoryId());
            if (category == null) throw new IllegalArgumentException("指标#" + metric.getId() + "关联的指标大类不存在");
            SystemInfo system = systemMap.get(category.getSystemId());
            result.add(new Target("metric", metric.getId(), value(system.getSystemCode()), value(system.getSystemName()), value(category.getCategoryCode()), value(category.getCategoryName()), value(metric.getSubCategoryCode()), value(metric.getSubCategoryName()), value(metric.getMetricCode()), value(metric.getMetricName()), value(metric.getUnit())));
        }
        result.sort(Comparator.comparing(Target::systemCode).thenComparing(Target::categoryCode).thenComparing(Target::subCode).thenComparing(Target::type).thenComparing(Target::metricCode).thenComparing(Target::id));
        return result;
    }

    private Map<String, List<AlarmStrategy>> groups(List<AlarmStrategy> all) {
        Map<String, List<AlarmStrategy>> result = new HashMap<>();
        all.stream().sorted(Comparator.comparing(AlarmStrategy::getId))
                .forEach(s -> result.computeIfAbsent(groupKey(s), key -> new ArrayList<>()).add(s));
        return result;
    }
    /**
     * 策略类型的唯一口径：显式 metric/health 优先；脚本直接改库写入空值、中文或其它别名时，
     * 按「对象类型 + 是否已有阈值/规则」推断，避免这些已配置策略在导出模板里凭空消失。
     * 纯指标对象的健康度策略极少（当前仅 1 条），无法自动区分时按指标值处理。
     */
    private String effectiveType(AlarmStrategy strategy) {
        String raw = value(strategy.getStrategyType()).trim();
        if (Set.of("metric", "指标值", "指标值策略").contains(raw)) return "metric";
        if (Set.of("health", "健康度", "健康度策略").contains(raw)) return "health";
        return "metric".equals(value(strategy.getTargetType()))
                && (hasConfiguredRule(strategy, false) || hasConfiguredRule(strategy, true)) ? "metric" : "health";
    }
    private boolean hasConfiguredRule(AlarmStrategy strategy, boolean ticket) {
        List<StrategyRule> rules = ticket ? strategy.getGongdanRules() : strategy.getAlarmRules();
        if (rules != null && !rules.isEmpty()) return true;
        return (ticket ? strategy.getGongdanThreshold() : strategy.getAlarmThreshold()) != null;
    }
    private String groupKey(AlarmStrategy strategy) { return effectiveType(strategy) + ":" + strategy.getTargetType() + ":" + strategy.getTargetId(); }
    private String groupKey(Target target, String type) { return type + ":" + target.key(); }
    private AlarmStrategy newStrategy(Target target, String type) {
        AlarmStrategy strategy = new AlarmStrategy(); strategy.setStrategyType(type); strategy.setTargetType(target.type());
        strategy.setTargetId(target.id()); strategy.setTargetName(target.name()); strategy.setStrategyName(defaultName(target, type));
        strategy.setStatus(1); strategy.setAlarmEnabled(0); strategy.setGongdanEnabled(0); return strategy;
    }
    private AlarmStrategy copy(AlarmStrategy source) { AlarmStrategy copy = new AlarmStrategy(); BeanUtils.copyProperties(source, copy); return copy; }
    private List<StrategyRule> copyRules(List<StrategyRule> source) { return new ArrayList<>(source.stream().map(rule -> { StrategyRule copy = new StrategyRule(); BeanUtils.copyProperties(rule, copy); return copy; }).toList()); }
    private String defaultName(Target target, String type) { String name = target.name() + "-" + typeLabel(type) + "-告警策略"; return name.length() <= 100 ? name : name.substring(0, 100); }
    private String typeLabel(String type) { return "health".equals(type) ? "健康度" : "指标值"; }
    private String targetLabel(String type) { return switch (type) { case "system" -> "系统"; case "category" -> "指标大类"; case "subCategory" -> "指标小类"; default -> "具体指标"; }; }
    private String parseType(String label) { return switch (label) { case "指标值" -> "metric"; case "健康度" -> "health"; default -> throw new IllegalArgumentException("策略类型必须是指标值或健康度"); }; }
    private String parseTargetType(String label) { return switch (label) { case "系统" -> "system"; case "指标大类" -> "category"; case "指标小类" -> "subCategory"; case "具体指标" -> "metric"; default -> throw new IllegalArgumentException("对象类型无效"); }; }
    private String enabledText(Integer enabled) { return Integer.valueOf(1).equals(enabled) ? "启用" : "停用"; }
    private int parseEnabled(String text) { return switch (text) { case "启用", "1" -> 1; case "停用", "0" -> 0; default -> throw new IllegalArgumentException("规则开关/策略状态必须为启用或停用"); }; }
    private Long optionalId(String text) { if (text.isBlank()) return null; long id = new BigDecimal(text).longValueExact(); if (id < 1) throw new IllegalArgumentException("ID必须为正整数"); return id; }
    private static String value(Object object) { return object == null ? "" : object.toString(); }
    private String text(Row row, int col, FormulaEvaluator evaluator) {
        if (row == null) return "";
        Cell cell = row.getCell(col); if (cell == null) return "";
        CellValue result = evaluator.evaluate(cell); if (result == null) return "";
        return switch (result.getCellType()) {
            case BLANK -> ""; case STRING -> result.getStringValue().trim();
            case NUMERIC -> NumberToTextConverter.toText(result.getNumberValue());
            case BOOLEAN -> Boolean.toString(result.getBooleanValue());
            default -> throw new IllegalArgumentException("第" + (col + 1) + "列包含错误值或无法计算的公式");
        };
    }
    private void requireCodes(Target target) {
        if (target.systemCode().isBlank() || (!"system".equals(target.type()) && target.categoryCode().isBlank())
                || ("subCategory".equals(target.type()) && target.subCode().isBlank()) || ("metric".equals(target.type()) && target.metricCode().isBlank()))
            throw new IllegalArgumentException("对象编码缺失，请先修正指标体系配置后重新导出");
    }
    private void verifyIdentity(Target supplied, Target target, String type) {
        if (!supplied.codeKey().equals(target.codeKey())) throw new IllegalArgumentException("对象编码与对象ID冲突，请勿修改身份列");
        String[] actual = {target.systemName(), target.categoryName(), target.subName(), target.metricName(), "health".equals(type) ? "分" : target.unit()};
        String[] input = {supplied.systemName(), supplied.categoryName(), supplied.subName(), supplied.metricName(), supplied.unit()};
        for (int i = 0; i < actual.length; i++) if (!actual[i].equals(input[i])) throw new IllegalArgumentException("对象名称或单位与当前配置不一致，请重新导出");
    }
    /** 展示/导入必须保留缺失比较条件的旧阈值，不能因为不可执行就当作没有配置。 */
    private List<StrategyRule> configuredRules(AlarmStrategy strategy, boolean ticket) {
        List<StrategyRule> rules = ticket ? strategy.getGongdanRules() : strategy.getAlarmRules();
        if (rules != null) return rules;
        BigDecimal threshold = ticket ? strategy.getGongdanThreshold() : strategy.getAlarmThreshold();
        if (threshold == null) return List.of();
        StrategyRule rule = new StrategyRule(); rule.setLevel(2); rule.setThreshold(threshold);
        rule.setCondition(ticket ? strategy.getGongdanCondition() : strategy.getAlarmCondition());
        Integer times = ticket ? strategy.getGongdanConsecutiveTimes() : strategy.getAlarmConsecutiveTimes();
        rule.setConsecutiveTimes(times == null ? 1 : times);
        return List.of(rule);
    }

    private String describe(StrategyRule rule) { return value(rule.getCondition()) + " " + value(rule.getThreshold()) + "；连续" + rule.getConsecutiveTimes() + "次有效更新异常"; }
    /** 审计表 operator 列为 varchar(50)：显示名再长也要先截断，否则整批导入因审计写入失败而回滚。 */
    private String auditOperator(String owner) {
        String name = owner == null || owner.isBlank() ? CurrentUserProvider.FALLBACK : owner.trim();
        return name.length() <= 50 ? name : name.substring(0, 50);
    }
    private String ruleState(List<StrategyRule> rules) {
        return rules.stream().sorted(Comparator.comparing(StrategyRule::getLevel)).map(rule -> rule.getLevel() + ":" + rule.getCondition() + ":"
                + (rule.getThreshold() == null ? "null" : rule.getThreshold().stripTrailingZeros().toPlainString()) + ":" + rule.getConsecutiveTimes()).toList().toString();
    }
    private String state(AlarmStrategy strategy) {
        return json(Arrays.asList(strategy.getStrategyName(), strategy.getStrategyType(), strategy.getTargetType(), strategy.getTargetId(), strategy.getStatus(),
                strategy.getAlarmEnabled(), strategy.getGongdanEnabled(), ruleState(configuredRules(strategy, false)), ruleState(configuredRules(strategy, true))));
    }
    private String version(List<AlarmStrategy> group) { return group.stream().sorted(Comparator.comparing(AlarmStrategy::getId)).map(s -> s.getId() + ":" + value(s.getUpdateTime()) + ":" + state(s)).toList().toString(); }
    private String json(Object object) { try { return mapper.writeValueAsString(object); } catch (Exception e) { throw new IllegalArgumentException("无法序列化策略", e); } }
}

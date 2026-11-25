package com.metric.collect.service;

import com.metric.collect.entity.Prompt;
import com.metric.collect.repository.PromptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 提示词服务：平台只保存纯文本，方便随时复制到智能体里使用。
 *
 * <p>不再维护「关联文件 / 状态 / 类型」：技能包已经由技能仓库统一管理，
 * 提示词和文件重新建立关联只会让同一份内容出现两个入口。</p>
 */
@Service
@RequiredArgsConstructor
public class PromptService {

    private final PromptRepository promptRepository;

    public Page<Prompt> findAll(Pageable pageable) {
        return promptRepository.findAll(pageable);
    }

    public Page<Prompt> searchByName(String name, Pageable pageable) {
        return promptRepository.findByNameContaining(name, pageable);
    }

    public Optional<Prompt> findById(Long id) {
        return promptRepository.findById(id);
    }

    @Transactional
    public Prompt create(Prompt prompt) {
        if (prompt.getStatus() == null || prompt.getStatus().isBlank()) {
            prompt.setStatus("ACTIVE");
        }
        if (prompt.getPromptType() == null || prompt.getPromptType().isBlank()) {
            prompt.setPromptType("SCRIPT_GENERATION");
        }
        return promptRepository.save(prompt);
    }

    /**
     * 只覆盖编辑区暴露的三个字段。
     *
     * <p>前端不再提交 status / promptType，直接 save 会把它们写成 null；
     * 这里按 id 取回原记录再复制字段，历史数据不会被清空。</p>
     */
    @Transactional
    public Prompt update(Long id, Prompt prompt) {
        Prompt existing = promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("提示词不存在"));
        existing.setName(prompt.getName());
        existing.setDescription(prompt.getDescription());
        existing.setContent(prompt.getContent());
        return promptRepository.save(existing);
    }

    @Transactional
    public void delete(Long id) {
        promptRepository.deleteById(id);
    }
}

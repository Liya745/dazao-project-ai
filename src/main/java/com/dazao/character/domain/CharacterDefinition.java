package com.dazao.character.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 角色资产的数据定义；工具权限和用户身份不由角色内容决定。 */
public record CharacterDefinition(
        @NotBlank(message = "请填写角色名称")
        @Size(max = 80, message = "名称最多 80 字")
        String name,
        @NotBlank(message = "请填写角色描述")
        @Size(max = 6000, message = "角色描述最多 6000 字")
        String description,
        @NotBlank(message = "请填写性格设定")
        @Size(max = 3000, message = "性格设定最多 3000 字")
        String personality,
        @Size(max = 3000)
        String scenario,
        @NotBlank(message = "请填写开场白")
        @Size(max = 2000)
        String firstMessage,
        @Size(max = 4000)
        String messageExample,
        @Size(max = 2000)
        String appearance,
        @Size(max = 2000)
        String speechStyle,
        @NotBlank(message = "请选择角色类型")
        @Pattern(regexp = "original|ip|assistant", message = "角色类型无效")
        String sourceType,
        @Size(max = 200)
        String work,
        @Size(max = 200)
        String canonVersion) {
}

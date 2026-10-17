package [=pkg];

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
<#if enumDef.description?has_content>
 * [=doc(enumDef.description)]
 *
</#if>
 * [=generatedNote]
 */
public enum [=enumDef.className] {
<#list enumDef.values as v>
    [=v.name]([=v.literal])<#if v?has_next>,<#else>;</#if>
</#list>

    private final [=enumDef.valueType] value;

    [=enumDef.className]([=enumDef.valueType] value) {
        this.value = value;
    }

    @JsonValue
    public [=enumDef.valueType] getValue() {
        return value;
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static [=enumDef.className] fromValue([=enumDef.valueType] value) {
        for ([=enumDef.className] candidate : values()) {
            if (candidate.value.equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unexpected value '" + value + "' for [=enumDef.className]");
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}

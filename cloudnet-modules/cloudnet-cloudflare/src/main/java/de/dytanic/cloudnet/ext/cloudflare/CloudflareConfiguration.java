package de.dytanic.cloudnet.ext.cloudflare;

import com.google.gson.reflect.TypeToken;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.lang.reflect.Type;
import java.util.Collection;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CloudflareConfiguration {

    public static final Type TYPE = new TypeToken<CloudflareConfiguration>() {
    }.getType();

    protected Collection<CloudflareConfigurationEntry> entries;

}
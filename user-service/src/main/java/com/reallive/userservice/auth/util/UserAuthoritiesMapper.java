package com.reallive.userservice.auth.util;

import com.reallive.userservice.auth.entity.UserEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.TreeSet;

@Component
public class UserAuthoritiesMapper {

    public List<String> map(UserEntity user) {
        TreeSet<String> authorities = new TreeSet<>();
        user.getRoles().forEach(role -> {
            authorities.add("ROLE_" + role.getName());
            role.getPermissions().forEach(permission -> authorities.add(permission.getName()));
        });
        return List.copyOf(authorities);
    }
}

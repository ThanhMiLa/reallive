package com.reallive.userservice.client.wallet;


import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class WalletCreationResponse {
    UUID id;
    UUID userId;
    Long balance;
}

package com.reallive.userservice.user.service;

import com.reallive.userservice.user.model.EmailVerificationEmail;

public interface EmailVerificationEmailPublisher {

    /** Returns after broker acknowledgement; email delivery remains asynchronous. */
    void publish(EmailVerificationEmail email);
}

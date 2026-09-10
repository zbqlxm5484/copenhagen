package com.copenhagen.pay.payment.credential.mandate;

import com.copenhagen.pay.payment.authorization.AuthorizationSession;

@FunctionalInterface
public interface PaymentCredentialIssuer {

    IssuedPaymentCredential issue(AuthorizationSession session, String authMethod);
}

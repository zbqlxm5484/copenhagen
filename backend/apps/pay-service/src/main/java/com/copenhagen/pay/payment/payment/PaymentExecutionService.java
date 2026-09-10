package com.copenhagen.pay.payment.payment;

import com.copenhagen.pay.payment.pg.PaymentGateway;
import com.copenhagen.pay.payment.pg.PaymentGateway.ChargeResult;
import org.springframework.stereotype.Service;

/** 결제 실행의 유일한 진입점입니다. PG 호출은 이 경계를 통해서만 수행합니다. */
@Service
public class PaymentExecutionService {

    private final PaymentExecutionPreparationService preparationService;
    private final PaymentExecutionSettlementService settlementService;
    private final PaymentGateway paymentGateway;

    public PaymentExecutionService(
            PaymentExecutionPreparationService preparationService,
            PaymentExecutionSettlementService settlementService,
            PaymentGateway paymentGateway) {
        this.preparationService = preparationService;
        this.settlementService = settlementService;
        this.paymentGateway = paymentGateway;
    }

    public PaymentExecutionResult execute(PaymentExecutionCommand command) {
        PreparedPaymentExecution prepared = preparationService.prepare(command);
        if (!prepared.requiresGatewayCall()) {
            return PaymentExecutionResult.from(prepared.existingPayment());
        }

        ChargeResult result;
        try {
            result = paymentGateway.charge(prepared.gatewayRequest());
        } catch (RuntimeException exception) {
            // PG 요청 도달 여부를 알 수 없으므로 예외를 실패로 단정하지 않습니다.
            result = ChargeResult.unknown("PG_ADAPTER_ERROR");
        }
        return settlementService.settle(prepared, result);
    }
}

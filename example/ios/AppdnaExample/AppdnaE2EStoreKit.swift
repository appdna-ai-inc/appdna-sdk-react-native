// The RN example host's StoreKit 2 transaction listing, mirroring the Flutter
// example's AppDelegate: `AppDNA-E2E unfinished=<productId:transactionId,…>` from
// `Transaction.unfinished` and `AppDNA-E2E all=<…>` from `Transaction.all`. Called by AppdnaE2EHost.m
// after a host buy, on every foreground and from the "Log unfinished transactions" button.
// Example-only code: nothing here ships in the SDK.

import Foundation
import StoreKit

@objc(AppdnaE2EStoreKit)
public final class AppdnaE2EStoreKit: NSObject {
    @objc public static func logTransactions(_ completion: (() -> Void)?) {
        Task {
            var unfinished: [String] = []
            for await result in Transaction.unfinished {
                if case .verified(let t) = result { unfinished.append("\(t.productID):\(t.id)") }
            }
            var all: [String] = []
            for await result in Transaction.all {
                if case .verified(let t) = result { all.append("\(t.productID):\(t.id)") }
            }
            NSLog("AppDNA-E2E unfinished=%@", unfinished.joined(separator: ","))
            NSLog("AppDNA-E2E all=%@", all.joined(separator: ","))
            completion?()
        }
    }
}

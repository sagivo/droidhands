#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Runs `block` and returns the Objective-C exception it raised, or nil. XCTest and private XCTest classes can raise
/// NSExceptions, which Swift can't catch; the runner must answer with an error instead of dying.
FOUNDATION_EXPORT NSException *_Nullable PBCatchException(NS_NOESCAPE void (^block)(void));

NS_ASSUME_NONNULL_END

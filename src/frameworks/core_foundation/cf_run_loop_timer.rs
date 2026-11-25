/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
//! `CFRunLoopTimer`.
//!
//! It's toll-free bridged with `NSTimer`.

use crate::abi::CallFromHost;
use crate::abi::GuestFunction;
use crate::dyld::{export_c_func, FunctionExports};
use crate::frameworks::core_foundation::cf_allocator::{kCFAllocatorDefault, CFAllocatorRef};
use crate::frameworks::core_foundation::cf_run_loop::{CFRunLoopMode, CFRunLoopRef};
use crate::frameworks::core_foundation::time::{CFAbsoluteTime, CFTimeInterval};
use crate::frameworks::core_foundation::CFIndex;
use crate::mem::{MutPtr, MutVoidPtr, SafeRead};
use crate::objc::{
    id, msg, msg_class, nil, objc_classes, retain, Class, ClassExports, HostObject, NSZonePtr,
};
use crate::Environment;

type CFRunLoopTimerRef = super::CFTypeRef;
type CFOptionFlags = u32;

// void (*void)(CFRunLoopTimerRef timer, void *info)
type CFRunLoopTimerCallBack = GuestFunction;

#[repr(C, packed)]
pub struct CFRunLoopTimerContext {
    version: CFIndex,
    info: MutVoidPtr,
    retain_callback: GuestFunction,
    release_callback: GuestFunction,
    copy_desc_callback: GuestFunction,
}
unsafe impl SafeRead for CFRunLoopTimerContext {}

fn CFRunLoopTimerCreate(
    env: &mut Environment,
    allocator: CFAllocatorRef,
    _fire_date: CFAbsoluteTime, // TODO
    interval: CFTimeInterval,
    flags: CFOptionFlags,
    order: CFIndex,
    callout: CFRunLoopTimerCallBack,
    context_ptr: MutPtr<CFRunLoopTimerContext>,
) -> CFRunLoopTimerRef {
    assert!(allocator == kCFAllocatorDefault || env.mem.read(allocator).is_system_default()); // unimplemented
    assert_eq!(flags, 0);
    assert_eq!(order, 0);

    let info: MutVoidPtr = if !context_ptr.is_null() {
        let context = env.mem.read(context_ptr);
        let version = context.version;
        assert_eq!(version, 0);

        // TODO: handle non-NULL callbacks
        let retain_callback = context.release_callback;
        assert!(retain_callback.to_ptr().is_null());
        let release_callback = context.release_callback;
        assert!(release_callback.to_ptr().is_null());
        let copy_desc_callback = context.copy_desc_callback;
        assert!(copy_desc_callback.to_ptr().is_null());

        context.info
    } else {
        MutVoidPtr::null()
    };

    let target: id = msg_class![env; _touchHLE_CFTimerTarget alloc];
    let target: id = msg![env; target initWithCallout:callout info:info];

    let selector = env.objc.lookup_selector("timerFireMethod:").unwrap();

    let repeats = interval > 0.0;
    let timer: id = msg_class![env; NSTimer timerWithTimeInterval:interval
                                                   target:target
                                                 selector:selector
                                                 userInfo:nil
                                                  repeats:repeats];
    // "Create" rule: the caller owns the timer, but the above is autoreleased.
    retain(env, timer)
}

fn CFRunLoopAddTimer(
    env: &mut Environment,
    run_loop: CFRunLoopRef,
    timer: CFRunLoopTimerRef,
    mode: CFRunLoopMode,
) {
    let run_loop_class: Class = msg![env; run_loop class];
    assert_eq!(
        run_loop_class,
        env.objc.get_known_class("NSRunLoop", &mut env.mem)
    );

    () = msg![env; run_loop addTimer:timer forMode:mode];
}

fn CFRunLoopTimerSetNextFireDate(
    env: &mut Environment,
    timer: CFRunLoopTimerRef,
    fire_date: CFAbsoluteTime,
) {
    let delay = fire_date - crate::frameworks::core_foundation::time::CFAbsoluteTimeGetCurrent(env);
    let host = env.objc.borrow_mut::<crate::frameworks::foundation::ns_timer::NSTimerHostObject>(timer);
    // An invalidated timer stays invalidated (due_by == None).
    if host.due_by.is_some() {
        host.due_by = Some(std::time::Instant::now() + std::time::Duration::from_secs_f64(delay.max(0.0)));
    }
}

fn CFRunLoopRemoveTimer(
    env: &mut Environment,
    run_loop: CFRunLoopRef,
    timer: CFRunLoopTimerRef,
    _mode: CFRunLoopMode, // only one mode is supported, see CFRunLoopAddTimer
) {
    if env.objc.terminating && !env.objc.object_exists(timer) {
        log!("Warning: ignoring CFRunLoopRemoveTimer on deallocated timer {:?} during app termination", timer);
        return;
    }
    crate::frameworks::foundation::ns_run_loop::detach_timer(env, run_loop, timer);
}

fn CFRunLoopTimerInvalidate(env: &mut Environment, timer: CFRunLoopTimerRef) {
    () = msg![env; timer invalidate];
}

pub const FUNCTIONS: FunctionExports = &[
    export_c_func!(CFRunLoopTimerCreate(_, _, _, _, _, _, _)),
    export_c_func!(CFRunLoopAddTimer(_, _, _)),
    export_c_func!(CFRunLoopRemoveTimer(_, _, _)),
    export_c_func!(CFRunLoopTimerInvalidate(_)),
    export_c_func!(CFRunLoopTimerSetNextFireDate(_, _)),
];

/// Belongs to _touchHLE_CFTimerTarget
struct CFTimerTargetHostObject {
    callout: GuestFunction,
    info: MutVoidPtr,
}
impl HostObject for CFTimerTargetHostObject {}

/// _touchHLE_CFTimerTarget serves as a convenience
/// object for performing a callout from a timer.
pub const CLASSES: ClassExports = objc_classes! {

(env, this, _cmd);

@implementation _touchHLE_CFTimerTarget: NSObject

+ (id)allocWithZone:(NSZonePtr)_zone {
    let host_object = Box::new(CFTimerTargetHostObject {
        callout: GuestFunction::null_ptr(),
        info: MutVoidPtr::null()
    });
    env.objc.alloc_object(this, host_object, &mut env.mem)
}

- (id)initWithCallout:(GuestFunction)callout info:(MutVoidPtr)info {
    let host_object: &mut CFTimerTargetHostObject = env.objc.borrow_mut(this);
    host_object.callout = callout;
    host_object.info = info;
    this
}

- (())timerFireMethod:(id)timer { // NSTimer *
    let &CFTimerTargetHostObject {
        callout,
        info
    } = env.objc.borrow(this);
    () = callout.call_from_host(env, (timer, info));
}

@end

};

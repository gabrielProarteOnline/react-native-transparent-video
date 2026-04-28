#import <React/RCTViewManager.h>

@interface RCT_EXTERN_MODULE(TransparentVideoViewManager, RCTViewManager)

RCT_EXPORT_VIEW_PROPERTY(src, NSDictionary);
RCT_EXPORT_VIEW_PROPERTY(loop, BOOL);
RCT_EXPORT_VIEW_PROPERTY(autoplay, BOOL);
RCT_EXPORT_VIEW_PROPERTY(muted, BOOL);
RCT_EXPORT_VIEW_PROPERTY(volume, float);
RCT_EXPORT_VIEW_PROPERTY(paused, BOOL);
RCT_EXPORT_VIEW_PROPERTY(progressUpdateInterval, NSNumber);
RCT_EXPORT_VIEW_PROPERTY(onEnd, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onLoad, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onError, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onProgress, RCTDirectEventBlock);
RCT_EXPORT_VIEW_PROPERTY(onPlaybackStateChange, RCTDirectEventBlock);

RCT_EXTERN_METHOD(seek:(nonnull NSNumber *)reactTag time:(nonnull NSNumber *)time toleranceMs:(nonnull NSNumber *)toleranceMs)
RCT_EXTERN_METHOD(play:(nonnull NSNumber *)reactTag)
RCT_EXTERN_METHOD(pause:(nonnull NSNumber *)reactTag)

@end

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define LOG_TAG "RT-Animator"

#include "Animator.h"

#include <inttypes.h>
#include <set>

#include "RenderNode.h"
#include "RenderProperties.h"

namespace android {
namespace uirenderer {

/************************************************************
 *  BaseRenderNodeAnimator
 ************************************************************/

BaseRenderNodeAnimator::BaseRenderNodeAnimator(float finalValue)
        : mFinalValue(finalValue)
        , mDeltaValue(0)
        , mFromValue(0)
        , mInterpolator(0)
        , mStagingPlayState(NOT_STARTED)
        , mPlayState(NOT_STARTED)
        , mHasStartValue(false)
        , mStartTime(0)
        , mDuration(300)
        , mStartDelay(0) {
}

BaseRenderNodeAnimator::~BaseRenderNodeAnimator() {
    delete mInterpolator;
}

void BaseRenderNodeAnimator::checkMutable() {
    // Should be impossible to hit as the Java-side also has guards for this
    LOG_ALWAYS_FATAL_IF(mStagingPlayState != NOT_STARTED,
            "Animator has already been started!");
}

void BaseRenderNodeAnimator::setInterpolator(Interpolator* interpolator) {
    checkMutable();
    delete mInterpolator;
    mInterpolator = interpolator;
}

void BaseRenderNodeAnimator::setStartValue(float value) {
    checkMutable();
    doSetStartValue(value);
}

void BaseRenderNodeAnimator::doSetStartValue(float value) {
    mFromValue = value;
    mDeltaValue = (mFinalValue - mFromValue);
    mHasStartValue = true;
}

void BaseRenderNodeAnimator::setDuration(nsecs_t duration) {
    checkMutable();
    mDuration = duration;
}

void BaseRenderNodeAnimator::setStartDelay(nsecs_t startDelay) {
    checkMutable();
    mStartDelay = startDelay;
}

void BaseRenderNodeAnimator::pushStaging(RenderNode* target, TreeInfo& info) {
    if (!mHasStartValue) {
        doSetStartValue(getValue(target));
    }
    if (mStagingPlayState > mPlayState) {
        mPlayState = mStagingPlayState;
        // Oh boy, we're starting! Man the battle stations!
        if (mPlayState == RUNNING) {
            transitionToRunning(info);
        }
    }
}

void BaseRenderNodeAnimator::transitionToRunning(TreeInfo& info) {
    LOG_ALWAYS_FATAL_IF(info.frameTimeMs <= 0, "%" PRId64 " isn't a real frame time!", info.frameTimeMs);
    if (mStartDelay < 0 || mStartDelay > 50000) {
        ALOGW("Your start delay is strange and confusing: %" PRId64, mStartDelay);
    }
    mStartTime = info.frameTimeMs + mStartDelay;
    if (mStartTime < 0) {
        ALOGW("Ended up with a really weird start time of %" PRId64
                " with frame time %" PRId64 " and start delay %" PRId64,
                mStartTime, info.frameTimeMs, mStartDelay);
        // Set to 0 so that the animate() basically instantly finishes
        mStartTime = 0;
    }
    // No interpolator was set, use the default
    if (!mInterpolator) {
        setInterpolator(Interpolator::createDefaultInterpolator());
    }
    if (mDuration < 0 || mDuration > 50000) {
        ALOGW("Your duration is strange and confusing: %" PRId64, mDuration);
    }
}

bool BaseRenderNodeAnimator::animate(RenderNode* target, TreeInfo& info) {
    if (mPlayState < RUNNING) {
        return false;
    }

    if (mStartTime > info.frameTimeMs) {
        info.out.hasAnimations |= true;
        return false;
    }

    float fraction = 1.0f;
    if (mPlayState == RUNNING && mDuration > 0) {
        fraction = (float)(info.frameTimeMs - mStartTime) / mDuration;
    }
    if (fraction >= 1.0f) {
        fraction = 1.0f;
        mPlayState = FINISHED;
    }

    fraction = mInterpolator->interpolate(fraction);
    setValue(target, mFromValue + (mDeltaValue * fraction));

    if (mPlayState == FINISHED) {
        callOnFinishedListener(info);
        return true;
    }

    info.out.hasAnimations |= true;
    return false;
}

void BaseRenderNodeAnimator::callOnFinishedListener(TreeInfo& info) {
    if (mListener.get()) {
        if (!info.animationHook) {
            mListener->onAnimationFinished(this);
        } else {
            info.animationHook->callOnFinished(this, mListener.get());
        }
    }
}

/************************************************************
 *  RenderPropertyAnimator
 ************************************************************/

struct RenderPropertyAnimator::PropertyAccessors {
   RenderNode::DirtyPropertyMask dirtyMask;
   GetFloatProperty getter;
   SetFloatProperty setter;
};

// Maps RenderProperty enum to accessors
const RenderPropertyAnimator::PropertyAccessors RenderPropertyAnimator::PROPERTY_ACCESSOR_LUT[] = {
    {RenderNode::TRANSLATION_X, &RenderProperties::getTranslationX, &RenderProperties::setTranslationX },
    {RenderNode::TRANSLATION_Y, &RenderProperties::getTranslationY, &RenderProperties::setTranslationY },
    {RenderNode::TRANSLATION_X, &RenderProperties::getTranslationZ, &RenderProperties::setTranslationZ },
    {RenderNode::SCALE_X, &RenderProperties::getScaleX, &RenderProperties::setScaleX },
    {RenderNode::SCALE_Y, &RenderProperties::getScaleY, &RenderProperties::setScaleY },
    {RenderNode::ROTATION, &RenderProperties::getRotation, &RenderProperties::setRotation },
    {RenderNode::ROTATION_X, &RenderProperties::getRotationX, &RenderProperties::setRotationX },
    {RenderNode::ROTATION_Y, &RenderProperties::getRotationY, &RenderProperties::setRotationY },
    {RenderNode::X, &RenderProperties::getX, &RenderProperties::setX },
    {RenderNode::Y, &RenderProperties::getY, &RenderProperties::setY },
    {RenderNode::Z, &RenderProperties::getZ, &RenderProperties::setZ },
    {RenderNode::ALPHA, &RenderProperties::getAlpha, &RenderProperties::setAlpha },
};

RenderPropertyAnimator::RenderPropertyAnimator(RenderProperty property, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mPropertyAccess(&(PROPERTY_ACCESSOR_LUT[property])) {
}

void RenderPropertyAnimator::onAttached(RenderNode* target) {
    if (!mHasStartValue
            && target->isPropertyFieldDirty(mPropertyAccess->dirtyMask)) {
        setStartValue((target->stagingProperties().*mPropertyAccess->getter)());
    }
    (target->mutateStagingProperties().*mPropertyAccess->setter)(finalValue());
}

uint32_t RenderPropertyAnimator::dirtyMask() {
    return mPropertyAccess->dirtyMask;
}

float RenderPropertyAnimator::getValue(RenderNode* target) const {
    return (target->properties().*mPropertyAccess->getter)();
}

void RenderPropertyAnimator::setValue(RenderNode* target, float value) {
    (target->animatorProperties().*mPropertyAccess->setter)(value);
}

/************************************************************
 *  CanvasPropertyPrimitiveAnimator
 ************************************************************/

CanvasPropertyPrimitiveAnimator::CanvasPropertyPrimitiveAnimator(
                CanvasPropertyPrimitive* property, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mProperty(property) {
}

float CanvasPropertyPrimitiveAnimator::getValue(RenderNode* target) const {
    return mProperty->value;
}

void CanvasPropertyPrimitiveAnimator::setValue(RenderNode* target, float value) {
    mProperty->value = value;
}

/************************************************************
 *  CanvasPropertySkPaintAnimator
 ************************************************************/

CanvasPropertyPaintAnimator::CanvasPropertyPaintAnimator(
                CanvasPropertyPaint* property, PaintField field, float finalValue)
        : BaseRenderNodeAnimator(finalValue)
        , mProperty(property)
        , mField(field) {
}

float CanvasPropertyPaintAnimator::getValue(RenderNode* target) const {
    switch (mField) {
    case STROKE_WIDTH:
        return mProperty->value.getStrokeWidth();
    case ALPHA:
        return mProperty->value.getAlpha();
    }
    LOG_ALWAYS_FATAL("Unknown field %d", (int) mField);
    return -1;
}

static uint8_t to_uint8(float value) {
    int c = (int) (value + .5f);
    return static_cast<uint8_t>( c < 0 ? 0 : c > 255 ? 255 : c );
}

void CanvasPropertyPaintAnimator::setValue(RenderNode* target, float value) {
    switch (mField) {
    case STROKE_WIDTH:
        mProperty->value.setStrokeWidth(value);
        return;
    case ALPHA:
        mProperty->value.setAlpha(to_uint8(value));
        return;
    }
    LOG_ALWAYS_FATAL("Unknown field %d", (int) mField);
}

} /* namespace uirenderer */
} /* namespace android */
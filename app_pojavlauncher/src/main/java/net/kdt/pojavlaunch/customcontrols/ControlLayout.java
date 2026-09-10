package net.kdt.pojavlaunch.customcontrols;

import static android.content.Context.INPUT_METHOD_SERVICE;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import static org.lwjgl.glfw.CallbackBridge.isGrabbing;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.gson.JsonSyntaxException;
import com.kdt.pickafile.FileListView;
import com.kdt.pickafile.FileSelectedListener;

import net.kdt.pojavlaunch.MinecraftGLSurface;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlButton;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlDrawer;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlInterface;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlJoystick;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlSubButton;
import net.kdt.pojavlaunch.customcontrols.handleview.ActionRow;
import net.kdt.pojavlaunch.customcontrols.handleview.ControlHandleView;
import net.kdt.pojavlaunch.customcontrols.buttons.ControlFps;
import net.kdt.pojavlaunch.customcontrols.handleview.EditControlSideDialog;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class ControlLayout extends FrameLayout {

	// ═══════════ SMART ALIGNMENT GUIDES v2 (edit mode) ═══════════
	// A real design-editor engine: every screen edge/centre and every other
	// control's edges/centre become snap candidates. While a control is dragged
	// the engine magnetically locks it to the CLOSEST candidate (never the first
	// one that happens to match, which is what caused the old jitter) and paints
	// razor-thin full-span guide lines with end caps plus an exact dp readout of
	// the gap to the control it locked onto.
	private static final float GUIDE_SNAP_PX_DP = 7f;
	private final java.util.ArrayList<float[]> mGuideSegments = new java.util.ArrayList<>(); // {x1,y1,x2,y2}
	private final java.util.ArrayList<Object[]> mGuideLabels = new java.util.ArrayList<>();  // {x,y,text}
	private android.graphics.Paint mGuideCorePaint, mGuideGlowPaint, mGuideCapPaint;
	private android.graphics.Paint mGuideLabelBgPaint, mGuideLabelTextPaint;
	private boolean mGuidesActive;
	private float mGuideAlpha = 0f;
	private android.animation.ValueAnimator mGuideAlphaAnimator;
	private String mLastSnapKey = null;
	private android.graphics.RectF mTmpRect = new android.graphics.RectF();

	private void ensureGuidePaints() {
		if (mGuideCorePaint != null) return;
		float d = getResources().getDisplayMetrics().density;
		mGuideCorePaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		mGuideCorePaint.setColor(0xF2E9EBF2);            // platinum core — razor thin
		mGuideCorePaint.setStrokeWidth(Math.max(1f, 0.8f * d));

		mGuideGlowPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		mGuideGlowPaint.setColor(0x22B9BEC9);            // whisper glow
		mGuideGlowPaint.setStrokeWidth(3f * d);

		mGuideCapPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		mGuideCapPaint.setColor(0xCCE9EBF2);
		mGuideCapPaint.setStrokeWidth(Math.max(1f, 1f * d));
		mGuideCapPaint.setStrokeCap(android.graphics.Paint.Cap.ROUND);

		mGuideLabelBgPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		mGuideLabelBgPaint.setColor(0xE8141519);
		mGuideLabelTextPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
		mGuideLabelTextPaint.setColor(0xFFD8DCE4);
		mGuideLabelTextPaint.setTextSize(9f * d);
		mGuideLabelTextPaint.setTextAlign(android.graphics.Paint.Align.CENTER);
		mGuideLabelTextPaint.setFakeBoldText(true);
		setWillNotDraw(false);
	}

	/** Visual (scale-aware) bounds of a view inside this layout. */
	private android.graphics.RectF visualRect(View v, android.graphics.RectF out) {
		float sx = v.getScaleX(), sy = v.getScaleY();
		float w = v.getWidth() * sx, h = v.getHeight() * sy;
		float l = v.getX() + v.getWidth() * (1f - sx) / 2f;
		float t = v.getY() + v.getHeight() * (1f - sy) / 2f;
		out.set(l, t, l + w, t + h);
		return out;
	}

	/** Candidate {position, ownerIndex(-1 = screen), anchorType(0 left/1 centre/2 right)}. */
	private static final class SnapCandidate {
		float pos; int owner; int type;
		SnapCandidate(float pos, int owner, int type) { this.pos = pos; this.owner = owner; this.type = type; }
	}

	private void collectCandidates(boolean vertical, View dragged,
								   java.util.List<SnapCandidate> out,
								   java.util.List<View> others) {
		out.clear();
		float w = getWidth(), h = getHeight();
		if (vertical) {
			out.add(new SnapCandidate(0f, -1, 0));
			out.add(new SnapCandidate(w / 2f, -1, 1));
			out.add(new SnapCandidate(w, -1, 2));
		} else {
			out.add(new SnapCandidate(0f, -1, 0));
			out.add(new SnapCandidate(h / 2f, -1, 1));
			out.add(new SnapCandidate(h, -1, 2));
		}
		android.graphics.RectF r = new android.graphics.RectF();
		for (int i = 0; i < others.size(); i++) {
			View o = others.get(i);
			if (o == dragged) continue;
			visualRect(o, r);
			if (vertical) {
				out.add(new SnapCandidate(r.left, i, 0));
				out.add(new SnapCandidate(r.centerX(), i, 1));
				out.add(new SnapCandidate(r.right, i, 2));
			} else {
				out.add(new SnapCandidate(r.top, i, 0));
				out.add(new SnapCandidate(r.centerY(), i, 1));
				out.add(new SnapCandidate(r.bottom, i, 2));
			}
		}
	}

	private java.util.List<View> visibleSiblings() {
		java.util.List<View> out = new java.util.ArrayList<>();
		for (ControlInterface ci : getButtonChildren()) {
			View v = ci.getControlView();
			if (v != null && v.getVisibility() == VISIBLE) out.add(v);
		}
		return out;
	}

	/**
	 * Magnetic alignment — call BEFORE the drag position is committed.
	 * Returns the corrected (x, y) the dragged view should be placed at.
	 */
	public float[] alignDrag(View dragged, float targetX, float targetY) {
		ensureGuidePaints();
		float d = getResources().getDisplayMetrics().density;
		float snap = GUIDE_SNAP_PX_DP * d;
		float dw = dragged.getWidth(), dh = dragged.getHeight();
		float sx = dragged.getScaleX(), sy = dragged.getScaleY();
		float vw = dw * sx, vh = dh * sy;
		float offX = dw * (1f - sx) / 2f, offY = dh * (1f - sy) / 2f;
		float left = targetX + offX, top = targetY + offY;

		java.util.List<View> others = visibleSiblings();
		java.util.List<SnapCandidate> cands = new java.util.ArrayList<>();

		// ── X axis ──
		collectCandidates(true, dragged, cands, others);
		float[] anchorsX = {left, left + vw / 2f, left + vw};
		float bestDist = Float.MAX_VALUE, bestDelta = 0f;
		int bestOwner = -2, bestAnchor = -1, bestType = -1;
		for (int a = 0; a < 3; a++) {
			for (SnapCandidate c : cands) {
				float dist = Math.abs(c.pos - anchorsX[a]);
				if (dist <= snap && dist < bestDist) {
					bestDist = dist; bestDelta = c.pos - anchorsX[a];
					bestOwner = c.owner; bestAnchor = a; bestType = c.type;
				}
			}
		}
		float newX = targetX;
		String keyX = "x:" + bestOwner + ":" + bestType + ":" + bestAnchor;
		if (bestOwner != -2) newX = targetX + bestDelta;

		// ── Y axis (recomputed with the X already corrected) ──
		collectCandidates(false, dragged, cands, others);
		float[] anchorsY = {top, top + vh / 2f, top + vh};
		float bestDistY = Float.MAX_VALUE, bestDeltaY = 0f;
		int bestOwnerY = -2, bestAnchorY = -1, bestTypeY = -1;
		for (int a = 0; a < 3; a++) {
			for (SnapCandidate c : cands) {
				float dist = Math.abs(c.pos - anchorsY[a]);
				if (dist <= snap && dist < bestDistY) {
					bestDistY = dist; bestDeltaY = c.pos - anchorsY[a];
					bestOwnerY = c.owner; bestAnchorY = a; bestTypeY = c.type;
				}
			}
		}
		float newY = targetY;
		String keyY = "y:" + bestOwnerY + ":" + bestTypeY + ":" + bestAnchorY;
		if (bestOwnerY != -2) newY = targetY + bestDeltaY;

		// One soft tick whenever a NEW lock engages.
		String key = keyX + "|" + keyY;
		boolean locked = (bestOwner != -2) || (bestOwnerY != -2);
		if (locked && !key.equals(mLastSnapKey)) {
			dragged.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
		}
		mLastSnapKey = locked ? key : null;

		return new float[]{newX, newY};
	}

	/** Draw-only pass: recomputes which lines are live and repaints them. */
	public void updateDragGuides(View dragged) {
		ensureGuidePaints();
		mGuideSegments.clear();
		mGuideLabels.clear();
		float d = getResources().getDisplayMetrics().density;
		float w = getWidth(), h = getHeight();
		if (w <= 0 || h <= 0) return;

		android.graphics.RectF me = visualRect(dragged, mTmpRect);
		java.util.List<View> others = visibleSiblings();
		java.util.List<SnapCandidate> cands = new java.util.ArrayList<>();
		float tol = 0.9f * d;
		String gapText = null;
		float gapX = 0, gapY = 0;

		// ── vertical guides ──
		collectCandidates(true, dragged, cands, others);
		float[] mineX = {me.left, me.centerX(), me.right};
		java.util.ArrayList<Float> linesX = new java.util.ArrayList<>();
		for (SnapCandidate c : cands) {
			for (float anchor : mineX) {
				if (Math.abs(c.pos - anchor) <= tol) {
					boolean dup = false;
					for (Float f : linesX) if (Math.abs(f - c.pos) <= tol) { dup = true; break; }
					if (!dup) linesX.add(c.pos);
					if (gapText == null && c.owner >= 0 && c.owner < others.size()) {
						View o = others.get(c.owner);
						android.graphics.RectF or = visualRect(o, new android.graphics.RectF());
						if (or.bottom < me.top) {
							float gapPx = me.top - or.bottom;
							if (gapPx >= 0 && gapPx < 200 * d) {
								gapText = Math.round(gapPx / d) + " dp";
								gapX = c.pos; gapY = (or.bottom + me.top) / 2f;
							}
						} else if (me.bottom < or.top) {
							float gapPx = or.top - me.bottom;
							if (gapPx >= 0 && gapPx < 200 * d) {
								gapText = Math.round(gapPx / d) + " dp";
								gapX = c.pos; gapY = (me.bottom + or.top) / 2f;
							}
						}
					}
					break;
				}
			}
		}
		for (Float x : linesX) mGuideSegments.add(new float[]{x, 0, x, h});

		// ── horizontal guides ──
		collectCandidates(false, dragged, cands, others);
		float[] mineY = {me.top, me.centerY(), me.bottom};
		java.util.ArrayList<Float> linesY = new java.util.ArrayList<>();
		for (SnapCandidate c : cands) {
			for (float anchor : mineY) {
				if (Math.abs(c.pos - anchor) <= tol) {
					boolean dup = false;
					for (Float f : linesY) if (Math.abs(f - c.pos) <= tol) { dup = true; break; }
					if (!dup) linesY.add(c.pos);
					break;
				}
			}
		}
		for (Float y : linesY) mGuideSegments.add(new float[]{0, y, w, y});

		if (gapText != null && !mGuideSegments.isEmpty()) {
			mGuideLabels.add(new Object[]{gapX, gapY, gapText});
		}

		boolean active = !mGuideSegments.isEmpty();
		if (active && !mGuidesActive) {
			mGuidesActive = true;
			animateGuideAlpha(1f);
		} else if (!active && mGuidesActive) {
			animateGuideAlpha(0f);
		} else {
			invalidate();
		}
	}

	/** Immediate, animation-free removal of every guide line + label. */
	private void dropDragGuidesNow() {
		if (mGuideAlphaAnimator != null) mGuideAlphaAnimator.cancel();
		mGuideAlphaAnimator = null;
		mGuideSegments.clear();
		mGuideLabels.clear();
		mGuideAlpha = 0f;
		mGuidesActive = false;
		mLastSnapKey = null;
		invalidate();
	}

	@Override
	protected void onDetachedFromWindow() {
		dropDragGuidesNow();
		super.onDetachedFromWindow();
	}

	/** Drag finished — guides fade out, then the segments are dropped. */
	public void clearDragGuides() {
		mLastSnapKey = null;
		if (!mGuidesActive && mGuideSegments.isEmpty()) return;
		animateGuideAlpha(0f);
	}

	private void animateGuideAlpha(float target) {
		if (mGuideAlphaAnimator != null) mGuideAlphaAnimator.cancel();
		final boolean dropAfter = target <= 0.01f;
		mGuideAlphaAnimator = android.animation.ValueAnimator.ofFloat(mGuideAlpha, target);
		mGuideAlphaAnimator.setDuration(dropAfter ? 160 : 120);
		mGuideAlphaAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.4f));
		mGuideAlphaAnimator.addUpdateListener(a -> {
			mGuideAlpha = (float) a.getAnimatedValue();
			invalidate();
		});
		mGuideAlphaAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
			@Override public void onAnimationEnd(android.animation.Animator animation) {
				if (dropAfter) {
					mGuideSegments.clear();
					mGuideLabels.clear();
					mGuidesActive = false;
					invalidate();
				}
			}
		});
		mGuideAlphaAnimator.start();
	}

	@Override
	protected void dispatchDraw(android.graphics.Canvas canvas) {
		super.dispatchDraw(canvas);
		if (mGuideCorePaint == null || mGuideSegments.isEmpty() || mGuideAlpha <= 0.01f) return;
		float d = getResources().getDisplayMetrics().density;
		int coreAlpha = (int) (mGuideCorePaint.getAlpha() * mGuideAlpha);
		int glowAlpha = (int) (mGuideGlowPaint.getAlpha() * mGuideAlpha);
		int capAlpha = (int) (mGuideCapPaint.getAlpha() * mGuideAlpha);
		for (float[] seg : mGuideSegments) {
			boolean vertical = Math.abs(seg[0] - seg[2]) < 0.5f;
			mGuideGlowPaint.setAlpha(glowAlpha);
			canvas.drawLine(seg[0], seg[1], seg[2], seg[3], mGuideGlowPaint);
			mGuideCorePaint.setAlpha(coreAlpha);
			canvas.drawLine(seg[0], seg[1], seg[2], seg[3], mGuideCorePaint);
			// end caps so the line reads as a measuring guide, not a stray pixel
			mGuideCapPaint.setAlpha((int) (capAlpha * 0.75f));
			float cap = 5f * d;
			if (vertical) {
				canvas.drawLine(seg[0] - cap, seg[1] + cap, seg[0] + cap, seg[1] + cap, mGuideCapPaint);
				canvas.drawLine(seg[0] - cap, seg[3] - cap, seg[0] + cap, seg[3] - cap, mGuideCapPaint);
			} else {
				canvas.drawLine(seg[0] + cap, seg[1] - cap, seg[0] + cap, seg[1] + cap, mGuideCapPaint);
				canvas.drawLine(seg[2] - cap, seg[3] - cap, seg[2] - cap, seg[3] + cap, mGuideCapPaint);
			}
			mGuideCorePaint.setAlpha(255);
			mGuideGlowPaint.setAlpha(255);
			mGuideCapPaint.setAlpha(255);
		}
		for (Object[] label : mGuideLabels) {
			float lx = (Float) label[0], ly = (Float) label[1];
			String text = (String) label[2];
			float tw = mGuideLabelTextPaint.measureText(text);
			float padX = 5f * d, padY = 3f * d;
			float boxL = lx - tw / 2f - padX, boxR = lx + tw / 2f + padX;
			float boxT = ly - 9f * d - padY, boxB = ly + padY;
			float radius = 5f * d;
			mGuideLabelBgPaint.setAlpha((int) (255 * mGuideAlpha));
			mGuideLabelTextPaint.setAlpha((int) (255 * mGuideAlpha));
			canvas.drawRoundRect(boxL, boxT, boxR, boxB, radius, radius, mGuideLabelBgPaint);
			canvas.drawText(text, lx, ly, mGuideLabelTextPaint);
			mGuideLabelBgPaint.setAlpha(255);
			mGuideLabelTextPaint.setAlpha(255);
		}
	}


	protected CustomControls mLayout;
	/* Accessible when inside the game by ControlInterface implementations, cached for perf. */
	private MinecraftGLSurface mGameSurface = null;

	/* Cache to buttons for performance purposes */
	private List<ControlInterface> mButtons;
	private boolean mModifiable = false;
	private boolean mIsModified;
	private boolean mControlVisible = false;

	private EditControlSideDialog mControlDialog = null;
	private ControlHandleView mHandleView;
	private ControlButtonMenuListener mMenuListener;

	// ── Real Undo/Redo (layout snapshots) ────────────────────────────────────
	// Every meaningful edit (add, select→edit/move/property change) first pushes
	// a JSON snapshot of the whole CustomControls. UNDO/REDO simply walk the
	// stacks and rebuild through loadLayout — no fake buttons, everything the
	// chips promise actually happens.
	private final java.util.ArrayDeque<String> mUndoStack = new java.util.ArrayDeque<>();
	private final java.util.ArrayDeque<String> mRedoStack = new java.util.ArrayDeque<>();
	private static final int UNDO_LIMIT = 32;
	private boolean mRestoringSnapshot = false;

	@androidx.annotation.Nullable
	private String snapshotJson() {
		try {
			if (mLayout == null) return null;
			return net.kdt.pojavlaunch.Tools.GLOBAL_GSON.toJson(mLayout);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Push the CURRENT layout state as an undo point (call BEFORE mutating). */
	public void pushUndoSnapshot() {
		if (mRestoringSnapshot) return;
		String snap = snapshotJson();
		if (snap == null) return;
		if (!mUndoStack.isEmpty() && snap.equals(mUndoStack.peek())) return;
		mUndoStack.push(snap);
		while (mUndoStack.size() > UNDO_LIMIT) mUndoStack.removeLast();
		mRedoStack.clear();
	}

	public boolean canUndo() { return mUndoStack.size() > 1; }
	public boolean canRedo() { return !mRedoStack.isEmpty(); }

	public void undo() {
		if (!canUndo()) return;
		mRedoStack.push(mUndoStack.pop());
		restoreSnapshot(mUndoStack.peek());
	}

	public void redo() {
		if (!canRedo()) return;
		String snap = mRedoStack.pop();
		mUndoStack.push(snap);
		restoreSnapshot(snap);
	}

	private void restoreSnapshot(@androidx.annotation.Nullable String json) {
		if (json == null) return;
		mRestoringSnapshot = true;
		try {
			CustomControls restored = net.kdt.pojavlaunch.Tools.GLOBAL_GSON
					.fromJson(json, CustomControls.class);
			loadLayout(restored);
		} catch (Throwable ignored) {
		} finally {
			mRestoringSnapshot = false;
		}
	}
	public ActionRow mActionRow = null;
	public String mLayoutFileName;

	public ControlLayout(Context ctx) {
		super(ctx);
	}

	public ControlLayout(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
	}


	public void loadLayout(String jsonPath) throws IOException, JsonSyntaxException {
		CustomControls layout = LayoutConverter.loadAndConvertIfNecessary(jsonPath);
		if(layout != null) {
			loadLayout(layout);
			updateLoadedFileName(jsonPath);
			return;
		}

		throw new IOException("Unsupported control layout version");
	}

	public void loadLayout(CustomControls controlLayout) {
		boolean sanitizedModified = false;
		if(controlLayout != null) {
			sanitizedModified = LayoutSanitizer.sanitizeLayout(controlLayout);
		}
		if(mActionRow == null){
			mActionRow = new ActionRow(getContext());
			addView(mActionRow);
		}

		removeAllButtons();
		if(mLayout != null) {
			mLayout.mControlDataList = null;
			mLayout = null;
		}

		mapTable.clear();

		// Cleanup buttons only when input layout is null
		if (controlLayout == null) return;

		mLayout = controlLayout;
		

		// Joystick(s) first, to workaround the touch dispatch
		for(ControlJoystickData joystick : mLayout.mJoystickDataList){
			addJoystickView(joystick);
		}

		//CONTROL BUTTON
		for (ControlData button : controlLayout.mControlDataList) {
			addControlView(button);
		}

		//CONTROL DRAWER
		for(ControlDrawerData drawerData : controlLayout.mDrawerDataList){
			ControlDrawer drawer = addDrawerView(drawerData);
			if(mModifiable) drawer.areButtonsVisible = true;
		}

		mLayout.scaledAt = LauncherPreferences.PREF_BUTTONSIZE;

		setModified(sanitizedModified);
		mButtons = null;
		getButtonChildren(); // Force refresh
	} // loadLayout

	//CONTROL BUTTON
	public void addControlButton(ControlData controlButton) {
		pushUndoSnapshot();
		mLayout.mControlDataList.add(controlButton);
		addControlView(controlButton);
	}

	private void addControlView(ControlData controlButton) {
		// Same ControlData, same list, same undo / save / move / resize pipeline —
		// only the view differs: the FPS read-out ticks a real value instead of
		// sending keys. Loaded layouts take this same path, so a saved FPS control
		// comes back as a live control and not as an inert button.
		final ControlButton view = controlButton.isFpsControl()
				? new ControlFps(this, controlButton)
				: new ControlButton(this, controlButton);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

		setModified(true);
	}

	// CONTROL DRAWER
	public void addDrawer(ControlDrawerData drawerData){
		pushUndoSnapshot();
		mLayout.mDrawerDataList.add(drawerData);
		addDrawerView();
	}

	private void addDrawerView(){
		addDrawerView(null);
	}

	private ControlDrawer addDrawerView(ControlDrawerData drawerData){

		final ControlDrawer view = new ControlDrawer(this,drawerData == null ? mLayout.mDrawerDataList.get(mLayout.mDrawerDataList.size()-1) : drawerData);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);
		//CONTROL SUB BUTTON
		for (ControlData subButton : view.getDrawerData().buttonProperties) {
			addSubView(view, subButton);
		}

		setModified(true);
		return view;
	}

	//CONTROL SUB-BUTTON
	public void addSubButton(ControlDrawer drawer, ControlData controlButton){
		//Yep there isn't much here
		drawer.getDrawerData().buttonProperties.add(controlButton);
		addSubView(drawer, drawer.getDrawerData().buttonProperties.get(drawer.getDrawerData().buttonProperties.size()-1 ));
	}

	private void addSubView(ControlDrawer drawer, ControlData controlButton){
		final ControlSubButton view = new ControlSubButton(this, controlButton, drawer);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}else{
			view.setVisible(true);
		}

		addView(view);
		drawer.addButton(view);


		setModified(true);
	}

	// JOYSTICK BUTTON
	public void addJoystickButton(ControlJoystickData data){
		pushUndoSnapshot();
		mLayout.mJoystickDataList.add(data);
		addJoystickView(data);
	}

	private void addJoystickView(ControlJoystickData data){
		ControlJoystick view = new ControlJoystick(this, data);

		if (!mModifiable) {
			view.setAlpha(view.getProperties().opacity);
			view.setFocusable(false);
			view.setFocusableInTouchMode(false);
		}
		addView(view);

	}


	private void removeAllButtons() {
		removeEditWindow();
		for(ControlInterface button : getButtonChildren()){
			removeView(button.getControlView());
		}

		// Removed views are reclaimed naturally; forcing a full GC here stalls gameplay.
	}

	public void saveLayout(String path) throws Exception {
		removeEditWindow();
		mLayout.save(path);
		setModified(false);
	}

	public void toggleControlVisible(){
		mControlVisible = !mControlVisible;
		setControlVisible(mControlVisible);
	}

	public float getLayoutScale(){
		return mLayout.scaledAt;
	}

	public CustomControls getLayout(){
		return mLayout;
	}

	public void setControlVisible(boolean isVisible) {
		if (mModifiable) return; // Not using on custom controls activity

		mControlVisible = isVisible;
		for(ControlInterface button : getButtonChildren()){
			button.setVisible(((button.getProperties().displayInGame && isGrabbing()) || (button.getProperties().displayInMenu && !isGrabbing())) && isVisible);
		}
	}

	public void setModifiable(boolean isModifiable) {
		if(!isModifiable){
			removeEditWindow();
			dropDragGuidesNow();
		}
		mModifiable = isModifiable;
		if(isModifiable){
			// In edit mode, all controls have to be shown
			for(ControlInterface button : getButtonChildren()){
				button.setVisible(true);
			}
		}
	}

	public boolean getModifiable(){
		return mModifiable;
	}

	public void setModified(boolean isModified) {
		mIsModified = isModified;
	}

	public List<ControlInterface> getButtonChildren(){
		if(mModifiable || mButtons == null){
			mButtons = new ArrayList<>();
			for(int i=0; i<getChildCount(); ++i){
				View v = getChildAt(i);
				if(v instanceof ControlInterface)
					mButtons.add(((ControlInterface) v));
			}
		}

		return mButtons;
	}

	public void refreshControlButtonPositions(){
		for(ControlInterface button : getButtonChildren()){
			button.setDynamicX(button.getProperties().dynamicX);
			button.setDynamicY(button.getProperties().dynamicY);
		}
	}

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        if(child instanceof ControlInterface && mControlDialog != null){
			mControlDialog.disappearColor();
            mControlDialog.disappear(false);
        }
    }

    /**
	 * Load the layout if needed, and pass down the burden of filling values
	 * to the button at hand.
	 */
	public void editControlButton(ControlInterface button){
		// First touch-point of every edit (move / resize / property change):
		// snapshot so the change is UNDO-able. Identical snapshots are
		// de-duplicated inside, so re-selecting without changes costs nothing.
		pushUndoSnapshot();
		if(mControlDialog == null){
			// When the panel is null, it needs to inflate first.
			// So inflate it, then process it on the next frame
			mControlDialog = new EditControlSideDialog(getContext(), this);
			post(() -> editControlButton(button));
			return;
		}

		mControlDialog.internalChanges = true;
		mControlDialog.setCurrentlyEditedButton(button);
		mEditOpenedAt = android.os.SystemClock.uptimeMillis();

		mControlDialog.appear(button.getControlView().getX() + button.getControlView().getWidth()/2f < currentDisplayMetrics.widthPixels/2f);
		button.loadEditValues(mControlDialog);

		mControlDialog.internalChanges = false;

		mControlDialog.disappearColor();

		if(mHandleView == null){
			mHandleView = new ControlHandleView(getContext());
			addView(mHandleView);
		}
		mHandleView.setControlButton(button);

		//mHandleView.show();
	}

	/** Swap the panel if the button position requires it */
	public void adaptPanelPosition(){
		if(mControlDialog != null) mControlDialog.adaptPanelPosition();
	}


	final HashMap<View, ControlInterface> mapTable = new HashMap<>();

	//While this is called onTouch, this should only be called from a ControlButton.
	public void onTouch(View v, MotionEvent ev) {
		ControlInterface lastControlButton = mapTable.get(v);

		// Map location to screen coordinates
		ev.offsetLocation(v.getX(), v.getY());


		//Check if the action is cancelling, reset the lastControl button associated to the view
		if (ev.getActionMasked() == MotionEvent.ACTION_UP
				|| ev.getActionMasked() == MotionEvent.ACTION_CANCEL
				|| ev.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
			if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
			mapTable.put(v, null);
			return;
		}

		if (ev.getActionMasked() != MotionEvent.ACTION_MOVE) return;


		//Optimization pass to avoid looking at all children again
		if (lastControlButton != null) {
			System.out.println("last control button check" + ev.getX() + "-" + ev.getY() + "-" + lastControlButton.getControlView().getX() + "-" + lastControlButton.getControlView().getY());
			if (ev.getX() > lastControlButton.getControlView().getX()
					&& ev.getX() < lastControlButton.getControlView().getX() + lastControlButton.getControlView().getWidth()
					&& ev.getY() > lastControlButton.getControlView().getY()
					&& ev.getY() < lastControlButton.getControlView().getY() + lastControlButton.getControlView().getHeight()) {
				return;
			}
		}

		//Release last keys
		if (lastControlButton != null) lastControlButton.sendKeyPresses(false);
		mapTable.remove(v);

		// Update the state of all swipeable buttons
		for (ControlInterface button : getButtonChildren()) {
			if (!button.getProperties().isSwipeable) continue;

			if (ev.getX() > button.getControlView().getX()
					&& ev.getX() < button.getControlView().getX() + button.getControlView().getWidth()
					&& ev.getY() > button.getControlView().getY()
					&& ev.getY() < button.getControlView().getY() + button.getControlView().getHeight()) {

				//Press the new key
				if (!button.equals(lastControlButton)) {
					button.sendKeyPresses(true);
					mapTable.put(v, button);
					return;
				}

			}
		}
	}

	/** Phase 10: uptime of the last editControlButton() — see onTouchEvent. */
	private long mEditOpenedAt;

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mModifiable && event.getActionMasked() != MotionEvent.ACTION_UP || mControlDialog == null)
			return true;
		// Phase 10: an UP that lands on the canvas within a third of a second of
		// the sheet being opened belongs to the select gesture itself (the
		// finger slid off the control) — it must not close what it just opened.
		if (mModifiable && android.os.SystemClock.uptimeMillis() - mEditOpenedAt < 350L)
			return true;

		removeEditWindow();
		return true;
	}

	public void removeEditWindow() {
		InputMethodManager imm = (InputMethodManager) getContext().getSystemService(INPUT_METHOD_SERVICE);

		// When the input window cannot be hidden, it returns false
		if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
		if(mControlDialog != null) {
			mControlDialog.disappearColor();
			mControlDialog.disappear(true);
		}

		if(mActionRow != null) mActionRow.setFollowedButton(null);
		if(mHandleView != null) mHandleView.hideImmediately();
	}

	public void save(String path){
		try {
			removeEditWindow();
			mLayout.save(path);
		} catch (IOException e) {Log.e("ControlLayout", "Failed to save the layout at:" + path);}
	}


	public boolean hasMenuButton() {
		for(ControlInterface controlInterface : getButtonChildren()){
			for (int keycode : controlInterface.getProperties().keycodes) {
				if (keycode == ControlData.SPECIALBTN_MENU) return true;
			}
		}
		return false;
	}

	public void setMenuListener(ControlButtonMenuListener menuListener) {
		this.mMenuListener = menuListener;
	}

	public void notifyAppMenu() {
		if(mMenuListener != null) mMenuListener.onClickedMenu();
	}

	/** Cached getter for perf purposes */
	public MinecraftGLSurface getGameSurface(){
		if(mGameSurface == null){
			mGameSurface = findViewById(R.id.main_game_render_view);
		}
		return mGameSurface;
	}

	public void askToExit(EditorExitable editorExitable) {
		if(mIsModified) {
			openSaveDialog(editorExitable);
		}else{
			openExitDialog(editorExitable);
		}
	}

	public void updateLoadedFileName(String path) {
		path = path.replace(Tools.CTRLMAP_PATH, ".");
		path = path.substring(0, path.length() - 5);
		mLayoutFileName = path;
	}

	public String saveToDirectory(String name) throws Exception{
		String jsonPath = Tools.CTRLMAP_PATH + "/" + name + ".json";
		saveLayout(jsonPath);
		return jsonPath;
	}

	class OnClickExitListener implements View.OnClickListener {
		private final AlertDialog mDialog;
		private final EditText mEditText;
		private final EditorExitable mListener;

		public OnClickExitListener(AlertDialog mDialog, EditText mEditText, EditorExitable mListener) {
			this.mDialog = mDialog;
			this.mEditText = mEditText;
			this.mListener = mListener;
		}

		@Override
		public void onClick(View v) {
			Context context = v.getContext();
			if (mEditText.getText().toString().isEmpty()) {
				mEditText.setError(context.getString(R.string.global_error_field_empty));
				return;
			}
			try {
				String jsonPath = saveToDirectory(mEditText.getText().toString());
				net.kdt.pojavlaunch.utils.CsPopup.show(context,
						context.getString(R.string.global_save) + ": " + jsonPath,
						android.R.drawable.ic_menu_save);
				mDialog.dismiss();
				if(mListener != null) mListener.exitEditor();
			} catch (Throwable th) {
				Tools.showError(context, th, mListener != null);
			}
		}
	}

	public void openSaveDialog(EditorExitable editorExitable) {
		final Context context = getContext();
		// Premium XML sheet with entrance/exit animations (user req: no stock popups).
		View sheet = LayoutInflater.from(context).inflate(R.layout.dialog_cs_save_layout, null);
		final EditText edit = sheet.findViewById(R.id.css_name_input);
		edit.setText(mLayoutFileName);

		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setView(sheet);
		final AlertDialog dialog = builder.create();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_cs_dialog);
			dialog.getWindow().setWindowAnimations(R.style.CsDialogAnim);
		}

		View saveExit = sheet.findViewById(R.id.css_btn_save_exit);
		saveExit.setVisibility(editorExitable != null ? View.VISIBLE : View.GONE);

		View.OnClickListener doSave = v -> {
			if (edit.getText().toString().trim().isEmpty()) {
				edit.setError(context.getString(R.string.global_error_field_empty));
				return;
			}
			try {
				String jsonPath = saveToDirectory(edit.getText().toString().trim());
				net.kdt.pojavlaunch.utils.CsPopup.show(context,
						context.getString(R.string.global_save) + ": " + jsonPath,
						android.R.drawable.ic_menu_save);
				dialog.dismiss();
				if (v.getId() == R.id.css_btn_save_exit && editorExitable != null)
					editorExitable.exitEditor();
			} catch (Throwable th) {
				Tools.showError(context, th, v.getId() == R.id.css_btn_save_exit);
			}
		};
		sheet.findViewById(R.id.css_btn_save).setOnClickListener(doSave);
		saveExit.setOnClickListener(doSave);
		sheet.findViewById(R.id.css_btn_cancel).setOnClickListener(v -> dialog.dismiss());

		dialog.show();
		if (dialog.getWindow() != null) {
			float d = getResources().getDisplayMetrics().density;
			int w = (int) Math.min(400 * d, getResources().getDisplayMetrics().widthPixels * 0.86f);
			dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
		}
	}

	public void openLoadDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
		builder.setTitle(R.string.global_load);
		builder.setPositiveButton(android.R.string.cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		if(Build.VERSION.SDK_INT < 29)flv.listFileAt(new File(Tools.CTRLMAP_PATH));
		else flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				try {
					loadLayout(path);
				}catch (IOException e) {
					Tools.showError(getContext(), e);
				}
				dialog.dismiss();
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	public void openSetDefaultDialog() {
		AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
		builder.setTitle(R.string.customctrl_selectdefault);
		builder.setPositiveButton(android.R.string.cancel, null);

		final AlertDialog dialog = builder.create();
		FileListView flv = new FileListView(dialog, "json");
		flv.lockPathAt(new File(Tools.CTRLMAP_PATH));
		flv.setFileSelectedListener(new FileSelectedListener(){

			@Override
			public void onFileSelected(File file, String path) {
				try {
					LauncherPreferences.DEFAULT_PREF.edit().putString("defaultCtrl", path).apply();
					LauncherPreferences.PREF_DEFAULTCTRL_PATH = path;loadLayout(path);
				}catch (IOException|JsonSyntaxException e) {
					Tools.showError(getContext(), e);
				}
				dialog.dismiss();
			}
		});
		dialog.setView(flv);
		dialog.show();
	}

	public void openExitDialog(EditorExitable exitListener) {
		final Context context = getContext();
		View sheet = LayoutInflater.from(context).inflate(R.layout.dialog_cs_exit_confirm, null);
		AlertDialog.Builder builder = new AlertDialog.Builder(context);
		builder.setView(sheet);
		final AlertDialog dialog = builder.create();
		if (dialog.getWindow() != null) {
			dialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_cs_dialog);
			dialog.getWindow().setWindowAnimations(R.style.CsDialogAnim);
		}
		sheet.findViewById(R.id.csx_btn_yes).setOnClickListener(v -> {
			dialog.dismiss();
			exitListener.exitEditor();
		});
		sheet.findViewById(R.id.csx_btn_no).setOnClickListener(v -> dialog.dismiss());
		dialog.show();
		if (dialog.getWindow() != null) {
			float d = getResources().getDisplayMetrics().density;
			int w = (int) Math.min(360 * d, getResources().getDisplayMetrics().widthPixels * 0.82f);
			dialog.getWindow().setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
		}
	}

	public boolean areControlVisible(){
		return mControlVisible;
	}
}

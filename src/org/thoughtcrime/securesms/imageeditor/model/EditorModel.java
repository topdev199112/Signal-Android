package org.thoughtcrime.securesms.imageeditor.model;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import org.thoughtcrime.securesms.imageeditor.Bounds;
import org.thoughtcrime.securesms.imageeditor.ColorableRenderer;
import org.thoughtcrime.securesms.imageeditor.Renderer;
import org.thoughtcrime.securesms.imageeditor.RendererContext;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Contains a reference to the root {@link EditorElement}, maintains undo and redo stacks and has a
 * reference to the {@link EditorElementHierarchy}.
 * <p>
 * As such it is the entry point for all operations that change the image.
 */
public final class EditorModel implements Parcelable, RendererContext.Ready {

  private static final Runnable NULL_RUNNABLE = () -> {
  };

  private static final int MINIMUM_OUTPUT_WIDTH = 0;

  private static final float MAXIMUM_CROP = 0.20f;

  @NonNull
  private Runnable invalidate = NULL_RUNNABLE;

  private final UndoRedoStacks undoRedoStacks;
  private final UndoRedoStacks cropUndoRedoStacks;
  private final InBoundsMemory inBoundsMemory = new InBoundsMemory();

  private EditorElementHierarchy editorElementHierarchy;

  private final RectF visibleViewPort = new RectF();
  private final Point size;

  public EditorModel() {
    this.size                   = new Point(1024, 1024);
    this.editorElementHierarchy = EditorElementHierarchy.create();
    this.undoRedoStacks         = new UndoRedoStacks(50);
    this.cropUndoRedoStacks     = new UndoRedoStacks(50);
  }

  private EditorModel(Parcel in) {
    ClassLoader classLoader     = getClass().getClassLoader();
    this.size                   = new Point(in.readInt(), in.readInt());
    this.editorElementHierarchy = EditorElementHierarchy.create(in.readParcelable(classLoader));
    this.undoRedoStacks         = in.readParcelable(classLoader);
    this.cropUndoRedoStacks     = in.readParcelable(classLoader);
  }

  public void setInvalidate(@Nullable Runnable invalidate) {
    this.invalidate = invalidate != null ? invalidate : NULL_RUNNABLE;
  }

  /**
   * Renders tree with the following matrix:
   * <p>
   * viewModelMatrix * matrix * editorMatrix
   * <p>
   * Child nodes are supplied with a viewModelMatrix' = viewModelMatrix * matrix * editorMatrix
   *
   * @param rendererContext Canvas to draw on to.
   */
  public void draw(@NonNull RendererContext rendererContext) {
    editorElementHierarchy.getRoot().draw(rendererContext);
  }

  public @Nullable Matrix findElementInverseMatrix(@NonNull EditorElement element, @NonNull Matrix viewMatrix) {
    Matrix inverse = new Matrix();
    if (findElement(element, viewMatrix, inverse)) {
      return inverse;
    }
    return null;
  }

  public @Nullable Matrix findElementMatrix(@NonNull EditorElement element, @NonNull Matrix viewMatrix) {
    Matrix inverse = findElementInverseMatrix(element, viewMatrix);
    if (inverse != null) {
      Matrix regular = new Matrix();
      inverse.invert(regular);
      return regular;
    }
    return null;
  }

  public EditorElement findElementAtPoint(@NonNull PointF point, @NonNull Matrix viewMatrix, @NonNull Matrix outInverseModelMatrix) {
    return editorElementHierarchy.getRoot().findElementAt(point.x, point.y, viewMatrix, outInverseModelMatrix);
  }

  private boolean findElement(@NonNull EditorElement element, @NonNull Matrix viewMatrix, @NonNull Matrix outInverseModelMatrix) {
    return editorElementHierarchy.getRoot().findElement(element, viewMatrix, outInverseModelMatrix) == element;
  }

  public void pushUndoPoint() {
    boolean cropping = isCropping();
    if (cropping && !currentCropIsAcceptable()) {
      return;
    }

    UndoRedoStacks stacks = cropping ? cropUndoRedoStacks : undoRedoStacks;

    if (stacks.getUndoStack().tryPush(editorElementHierarchy.getRoot())) {
      stacks.getRedoStack().clear();
    }
  }

  public void undo() {
    boolean        cropping = isCropping();
    UndoRedoStacks stacks   = cropping ? cropUndoRedoStacks : undoRedoStacks;

    undoRedo(stacks.getUndoStack(), stacks.getRedoStack(), cropping);
  }

  public void redo() {
    boolean        cropping = isCropping();
    UndoRedoStacks stacks   = cropping ? cropUndoRedoStacks : undoRedoStacks;

    undoRedo(stacks.getRedoStack(), stacks.getUndoStack(), cropping);
  }

  private void undoRedo(@NonNull ElementStack fromStack, @NonNull ElementStack toStack, boolean keepEditorState) {
    final EditorElement popped = fromStack.pop();
    if (popped != null) {
      EditorElement oldRootElement = editorElementHierarchy.getRoot();
      editorElementHierarchy = EditorElementHierarchy.create(popped);
      toStack.tryPush(oldRootElement);

      restoreStateWithAnimations(oldRootElement, editorElementHierarchy.getRoot(), invalidate, keepEditorState);
      invalidate.run();

      // re-zoom image root as the view port might be different now
      editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);

      inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
    }
  }

  private static void restoreStateWithAnimations(@NonNull EditorElement fromRootElement, @NonNull EditorElement toRootElement, @NonNull Runnable onInvalidate, boolean keepEditorState) {
    Map<UUID, EditorElement> fromMap = getElementMap(fromRootElement);
    Map<UUID, EditorElement> toMap   = getElementMap(toRootElement);

    for (EditorElement fromElement : fromMap.values()) {
      fromElement.stopAnimation();
      EditorElement toElement = toMap.get(fromElement.getId());
      if (toElement != null) {
        toElement.animateFrom(fromElement.getLocalMatrixAnimating(), onInvalidate);

        if (keepEditorState) {
          toElement.getEditorMatrix().set(fromElement.getEditorMatrix());
          toElement.getFlags().set(fromElement.getFlags());
        }
      } else {
        // element is removed
        EditorElement parentFrom = fromRootElement.parentOf(fromElement);
        if (parentFrom != null) {
          EditorElement toParent = toMap.get(parentFrom.getId());
          if (toParent != null) {
            toParent.addDeletedChildFadingOut(fromElement, onInvalidate);
          }
        }
      }
    }

    for (EditorElement toElement : toMap.values()) {
      if (!fromMap.containsKey(toElement.getId())) {
        // new item
        toElement.animateFadeIn(onInvalidate);
      }
    }
  }

  private static Map<UUID, EditorElement> getElementMap(@NonNull EditorElement element) {
    final Map<UUID, EditorElement> result = new HashMap<>();
    element.buildMap(result);
    return result;
  }

  public void startCrop() {
    pushUndoPoint();
    cropUndoRedoStacks.getUndoStack().clear();
    cropUndoRedoStacks.getUndoStack().clear();
    editorElementHierarchy.startCrop(invalidate);
    inBoundsMemory.push(editorElementHierarchy.getMainImage(), editorElementHierarchy.getCropEditorElement());
  }

  public void doneCrop() {
    editorElementHierarchy.doneCrop(visibleViewPort, invalidate);
  }

  public void setCropAspectLock(boolean locked) {
    EditorFlags flags = editorElementHierarchy.getCropEditorElement().getFlags();
    int currentState  = flags.setAspectLocked(locked).getCurrentState();

    flags.reset();
    flags.setAspectLocked(locked)
         .persist();
    flags.restoreState(currentState);
  }

  public boolean isCropAspectLocked() {
    return editorElementHierarchy.getCropEditorElement().getFlags().isAspectLocked();
  }

  public void postEdit(boolean allowScaleToRepairCrop) {
    if (isCropping()) {
      ensureFitsBounds(allowScaleToRepairCrop);
    }
  }

  private void ensureFitsBounds(boolean allowScaleToRepairCrop) {
    EditorElement mainImage = editorElementHierarchy.getMainImage();
    if (mainImage == null) return;

    EditorElement cropEditorElement = editorElementHierarchy.getCropEditorElement();

    if (!currentCropIsAcceptable()) {
      if (allowScaleToRepairCrop) {
        if (!tryToScaleToFit(cropEditorElement, 0.9f)) {
          tryToScaleToFit(mainImage, 2f);
        }
      }

      if (!currentCropIsAcceptable()) {
        inBoundsMemory.restore(mainImage, cropEditorElement, invalidate);
      } else {
        inBoundsMemory.push(mainImage, cropEditorElement);
      }
    }

    editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate);
  }

  /**
   * Attempts to scale the supplied element such that {@link #cropIsWithinMainImageBounds} is true.
   * <p>
   * Does not respect minimum scale, so does need a further check to {@link #currentCropIsAcceptable} afterwards.
   *
   * @param element     The element to be scaled. If successful, it will be animated to the correct position.
   * @param scaleAtMost The amount of scale to apply at most. Use < 1 for the crop, and > 1 for the image.
   * @return true if successfully scaled the element. false if the element was left unchanged.
   */
  private boolean tryToScaleToFit(@NonNull EditorElement element, float scaleAtMost) {
    Matrix  elementMatrix     = element.getLocalMatrix();
    Matrix  original          = new Matrix(elementMatrix);
    Matrix  lastSuccessful    = new Matrix();
    boolean success           = false;
    float   unsuccessfulScale = 1;
    int     attempt           = 0;

    do {
      float tryScale = (scaleAtMost + unsuccessfulScale) / 2f;
      elementMatrix.set(original);
      elementMatrix.preScale(tryScale, tryScale);

      if (cropIsWithinMainImageBounds(editorElementHierarchy)) {
        scaleAtMost = tryScale;
        success = true;
        lastSuccessful.set(elementMatrix);
      } else {
        unsuccessfulScale = tryScale;
      }
      attempt++;
    } while (attempt < 16 && Math.abs(scaleAtMost - unsuccessfulScale) > 0.001f);

    elementMatrix.set(original);
    if (success) {
      element.animateLocalTo(lastSuccessful, invalidate);
    }
    return success;
  }

  public void dragDropRelease() {
    editorElementHierarchy.dragDropRelease(visibleViewPort, invalidate);
  }

  /**
   * Pixel count must be no smaller than the MAXIMUM_CROP of its original size and all points must be within the bounds.
   */
  private boolean currentCropIsAcceptable() {
    Point outputSize        = getOutputSize();
    int   outputPixelCount  = outputSize.x * outputSize.y;
    int   minimumPixelCount = (int) (size.x * size.y * MAXIMUM_CROP * MAXIMUM_CROP);

    return outputPixelCount >= minimumPixelCount &&
           cropIsWithinMainImageBounds(editorElementHierarchy);
  }

  /**
   * @return true if and only if the current crop rect is fully in the bounds.
   */
  private static boolean cropIsWithinMainImageBounds(@NonNull EditorElementHierarchy hierarchy) {
    return Bounds.boundsRemainInBounds(hierarchy.imageMatrixRelativeToCrop());
  }

  /**
   * Called as edits are underway.
   */
  public void moving(@NonNull EditorElement editorElement) {
    if (!isCropping()) return;

    EditorElement mainImage = editorElementHierarchy.getMainImage();
    EditorElement cropEditorElement = editorElementHierarchy.getCropEditorElement();

    if (editorElement == mainImage || editorElement == cropEditorElement) {
      if (currentCropIsAcceptable()) {
        inBoundsMemory.push(mainImage, cropEditorElement);
      }
    }
  }

  public void setVisibleViewPort(@NonNull RectF visibleViewPort) {
    this.visibleViewPort.set(visibleViewPort);
    this.editorElementHierarchy.updateViewToCrop(visibleViewPort, invalidate);
  }

  public Set<Integer> getUniqueColorsIgnoringAlpha() {
    final Set<Integer> colors = new LinkedHashSet<>();

    editorElementHierarchy.getRoot().forAllInTree(element -> {
      Renderer renderer = element.getRenderer();
      if (renderer instanceof ColorableRenderer) {
        colors.add(((ColorableRenderer) renderer).getColor() | 0xff000000);
      }
    });

    return colors;
  }

  public static final Creator<EditorModel> CREATOR = new Creator<EditorModel>() {
    @Override
    public EditorModel createFromParcel(Parcel in) {
      return new EditorModel(in);
    }

    @Override
    public EditorModel[] newArray(int size) {
      return new EditorModel[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeInt(size.x);
    dest.writeInt(size.y);
    dest.writeParcelable(editorElementHierarchy.getRoot(), flags);
    dest.writeParcelable(undoRedoStacks, flags);
    dest.writeParcelable(cropUndoRedoStacks, flags);
  }

  /**
   * Blocking render of the model.
   */
  @WorkerThread
  public Bitmap render(@NonNull Context context) {
    EditorElement image      = editorElementHierarchy.getFlipRotate();
    RectF         cropRect   = editorElementHierarchy.getCropRect();
    Point         outputSize = getOutputSize();

    Bitmap bitmap = Bitmap.createBitmap(outputSize.x, outputSize.y, Bitmap.Config.ARGB_8888);
    try {
      Canvas canvas = new Canvas(bitmap);
      RendererContext rendererContext = new RendererContext(context, canvas, RendererContext.Ready.NULL, RendererContext.Invalidate.NULL);

      RectF bitmapArea = new RectF();
      bitmapArea.right = bitmap.getWidth();
      bitmapArea.bottom = bitmap.getHeight();

      Matrix viewMatrix = new Matrix();
      viewMatrix.setRectToRect(cropRect, bitmapArea, Matrix.ScaleToFit.FILL);

      rendererContext.setIsEditing(false);
      rendererContext.setBlockingLoad(true);

      EditorElement overlay = editorElementHierarchy.getOverlay();
      overlay.getFlags().setVisible(false).setChildrenVisible(false);

      try {
        rendererContext.canvasMatrix.initial(viewMatrix);
        image.draw(rendererContext);
      } finally {
        overlay.getFlags().reset();
      }
    } catch (Exception e) {
      bitmap.recycle();
      throw e;
    }
    return bitmap;
  }

  @NonNull
  private Point getOutputSize() {
    PointF outputSize = editorElementHierarchy.getOutputSize(size);

    int width  = (int) Math.max(MINIMUM_OUTPUT_WIDTH, outputSize.x);
    int height = (int) (width * outputSize.y / outputSize.x);

    return new Point(width, height);
  }

  @Override
  public void onReady(@NonNull Renderer renderer, @Nullable Matrix cropMatrix, @Nullable Point size) {
    if (cropMatrix != null && size != null && isRendererOfMainImage(renderer)) {
      Matrix imageCropMatrix = editorElementHierarchy.getImageCrop().getLocalMatrix();
      this.size.set(size.x, size.y);
      if (imageCropMatrix.isIdentity()) {
        imageCropMatrix.set(cropMatrix);
        editorElementHierarchy.doneCrop(visibleViewPort, null);
      }
    }
  }

  private boolean isRendererOfMainImage(@NonNull Renderer renderer) {
    EditorElement mainImage         = editorElementHierarchy.getMainImage();
    Renderer      mainImageRenderer = mainImage != null ? mainImage.getRenderer() : null;
    return mainImageRenderer == renderer;
  }

  /**
   * Add a new {@link EditorElement} centered in the current visible crop area.
   *
   * @param element New element to add.
   * @param scale   Initial scale for new element.
   */
  public void addElementCentered(@NonNull EditorElement element, float scale) {
    Matrix localMatrix = element.getLocalMatrix();

    editorElementHierarchy.getMainImageFullMatrix().invert(localMatrix);

    localMatrix.preScale(scale, scale);
    addElement(element);
  }

  /**
   * Add an element to the main image, or if there is no main image, make the new element the main image.
   *
   * @param element New element to add.
   */
  public void addElement(@NonNull EditorElement element) {
    pushUndoPoint();

    EditorElement mainImage = editorElementHierarchy.getMainImage();
    EditorElement parent    = mainImage != null ? mainImage : editorElementHierarchy.getImageRoot();

    parent.addElement(element);

    if (parent != mainImage) {
      undoRedoStacks.getUndoStack().clear();
    }
  }

  public boolean isChanged() {
    ElementStack undoStack = undoRedoStacks.getUndoStack();
    return !undoStack.isEmpty() || undoStack.isOverflowed();
  }

  public RectF findCropRelativeToRoot() {
    return findCropRelativeTo(editorElementHierarchy.getRoot());
  }

  RectF findCropRelativeTo(EditorElement element) {
    return findRelativeBounds(editorElementHierarchy.getCropEditorElement(), element);
  }

  RectF findRelativeBounds(EditorElement from, EditorElement to) {
    Matrix relative = findRelativeMatrix(from, to);

    RectF dst = new RectF(Bounds.FULL_BOUNDS);
    if (relative != null) {
      relative.mapRect(dst, Bounds.FULL_BOUNDS);
    }
    return dst;
  }

  /**
   * Returns a matrix that maps points in the {@param from} element in to points in the {@param to} element.
   *
   * @param from
   * @param to
   * @return
   */
  @Nullable Matrix findRelativeMatrix(@NonNull EditorElement from, @NonNull EditorElement to) {
    Matrix matrix = findElementInverseMatrix(to, new Matrix());
    Matrix outOf  = findElementMatrix(from, new Matrix());

    if (outOf != null && matrix != null) {
      matrix.preConcat(outOf);
      return matrix;
    }
    return null;
  }

  public void rotate90clockwise() {
    pushUndoPoint();
    editorElementHierarchy.flipRotate(90, 1, 1, visibleViewPort, invalidate);
  }

  public void rotate90anticlockwise() {
    pushUndoPoint();
    editorElementHierarchy.flipRotate(-90, 1, 1, visibleViewPort, invalidate);
  }

  public void flipHorizontal() {
    pushUndoPoint();
    editorElementHierarchy.flipRotate(0, -1, 1, visibleViewPort, invalidate);
  }

  public void flipVertical() {
    pushUndoPoint();
    editorElementHierarchy.flipRotate(0, 1, -1, visibleViewPort, invalidate);
  }

  public EditorElement getRoot() {
    return editorElementHierarchy.getRoot();
  }

  public void delete(@NonNull EditorElement editorElement) {
    editorElementHierarchy.getImageRoot().forAllInTree(element -> element.deleteChild(editorElement, invalidate));
  }

  public @Nullable EditorElement findById(@NonNull UUID uuid) {
    return getElementMap(getRoot()).get(uuid);
  }

  /**
   * Changes the temporary view so that the element is centered in it.
   *
   * @param entity Entity to center on.
   * @param y      An optional extra value to translate the view by to leave space for the keyboard for example.
   * @param doNotZoomOut Iff true, undoes any zoom out
   */
  public void zoomTo(@NonNull EditorElement entity, float y, boolean doNotZoomOut) {
    Matrix elementInverseMatrix = findElementInverseMatrix(entity, new Matrix());
    if (elementInverseMatrix != null) {
      elementInverseMatrix.preConcat(editorElementHierarchy.getRoot().getEditorMatrix());

      float xScale = EditorElementHierarchy.xScale(elementInverseMatrix);
      if (doNotZoomOut && xScale < 1) {
        elementInverseMatrix.postScale(1 / xScale, 1 / xScale);
      }

      elementInverseMatrix.postTranslate(0, y);

      editorElementHierarchy.getRoot().animateEditorTo(elementInverseMatrix, invalidate);
    }
  }

  public void zoomOut() {
    editorElementHierarchy.getRoot().rollbackEditorMatrix(invalidate);
  }

  public void indicateSelected(@NonNull EditorElement selected) {
    selected.singleScalePulse(invalidate);
  }

  public boolean isCropping() {
    return editorElementHierarchy.getCropEditorElement().getFlags().isVisible();
  }
}

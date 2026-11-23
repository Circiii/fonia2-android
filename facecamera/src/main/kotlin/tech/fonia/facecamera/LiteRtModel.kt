package tech.fonia.facecamera

import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.InterpreterApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * One TFLite model on LiteRT's classic interpreter, CPU with XNNPACK, from the runtime bundled in the APK (never
 * the Google Play services copy). Input and outputs are buffers allocated once: [input] is copied into the input
 * tensor with one memcpy, and [outputs] are read-only views of the output tensors, so a run allocates nothing here.
 */
internal class LiteRtModel(model: ByteBuffer, threads: Int) : AutoCloseable {
    private val interpreter: Interpreter = Interpreter(
        model,
        Interpreter.Options().apply {
            setNumThreads(threads)
            setUseXNNPACK(true)
            setRuntime(InterpreterApi.Options.TfLiteRuntime.FROM_APPLICATION_ONLY)
        },
    )
    private val inputBuffer: ByteBuffer
    val input: FloatBuffer
    val outputs: Array<FloatBuffer>
    private val inputs: Array<Any>
    private val outputMap = HashMap<Int, Any?>()

    init {
        interpreter.allocateTensors()
        inputBuffer = ByteBuffer.allocateDirect(interpreter.getInputTensor(0).numBytes()).order(ByteOrder.nativeOrder())
        input = inputBuffer.asFloatBuffer()
        outputs = Array(interpreter.outputTensorCount) {
            interpreter.getOutputTensor(it).asReadOnlyBuffer().order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        inputs = arrayOf(inputBuffer)
        // Non-empty, as the interpreter requires; a null value means "leave the output in the tensor".
        outputMap[0] = null
    }

    fun inputSize(): Int = input.capacity()

    fun outputSize(index: Int): Int = outputs[index].capacity()

    /** Copies [values] into the input and runs the model. */
    fun run(values: FloatArray) {
        input.clear()
        input.put(values, 0, input.capacity())
        inputBuffer.rewind()
        interpreter.runForMultipleInputsOutputs(inputs, outputMap)
    }

    override fun close() = interpreter.close()
}

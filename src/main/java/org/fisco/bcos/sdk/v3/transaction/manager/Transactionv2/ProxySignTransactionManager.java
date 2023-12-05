package org.fisco.bcos.sdk.v3.transaction.manager.Transactionv2;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.concurrent.CompletableFuture;
import org.fisco.bcos.sdk.jni.common.JniException;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionBuilderV2JniObj;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.client.protocol.model.TransactionAttribute;
import org.fisco.bcos.sdk.v3.client.protocol.request.Transaction;
import org.fisco.bcos.sdk.v3.client.protocol.response.Call;
import org.fisco.bcos.sdk.v3.crypto.hash.Hash;
import org.fisco.bcos.sdk.v3.crypto.hash.Keccak256;
import org.fisco.bcos.sdk.v3.crypto.hash.SM3Hash;
import org.fisco.bcos.sdk.v3.model.CryptoType;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.model.callback.RespCallback;
import org.fisco.bcos.sdk.v3.model.callback.TransactionCallback;
import org.fisco.bcos.sdk.v3.transaction.gasProvider.ContractGasProvider;
import org.fisco.bcos.sdk.v3.transaction.gasProvider.DefaultGasProvider;
import org.fisco.bcos.sdk.v3.transaction.gasProvider.EIP1559Struct;
import org.fisco.bcos.sdk.v3.transaction.signer.AsyncTransactionSignercInterface;
import org.fisco.bcos.sdk.v3.transaction.signer.TransactionJniSignerService;
import org.fisco.bcos.sdk.v3.utils.Hex;
import org.fisco.bcos.sdk.v3.utils.Numeric;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ProxySignTransactionManager: customizable signer method, default use JNI signer. customizable key
 * pair to sign, default use client key pair.
 */
public class ProxySignTransactionManager extends TransactionManager {

    private ContractGasProvider contractGasProvider = new DefaultGasProvider();

    private AsyncTransactionSignercInterface asyncTxSigner = null;

    private static Logger logger = LoggerFactory.getLogger(ProxySignTransactionManager.class);

    private int cryptoType = CryptoType.ECDSA_TYPE;

    private Hash hashImpl;

    public ProxySignTransactionManager(Client client) {
        super(client);
        cryptoType = client.getCryptoSuite().cryptoTypeConfig;
        hashImpl = (cryptoType == CryptoType.ECDSA_TYPE) ? new Keccak256() : new SM3Hash();
        asyncTxSigner = new TransactionJniSignerService(client.getCryptoSuite().getCryptoKeyPair());
    }

    @Override
    protected ContractGasProvider getGasProvider() {
        return contractGasProvider;
    }

    @Override
    protected void steGasProvider(ContractGasProvider gasProvider) {
        contractGasProvider = gasProvider;
    }

    public ProxySignTransactionManager(
            Client client, AsyncTransactionSignercInterface asyncTxSigner) {
        super(client);
        this.asyncTxSigner = asyncTxSigner;
    }

    public void setAsyncTransactionSigner(AsyncTransactionSignercInterface asyncTxSigner) {
        this.asyncTxSigner = asyncTxSigner;
    }

    /**
     * Send tx with abi field
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @return receipt
     */
    @Override
    protected TransactionReceipt sendTransaction(
            String to, String data, BigInteger value, String abi, boolean constructor)
            throws JniException {
        String strippedData = Hex.trimPrefix(data);
        String methodId = strippedData.length() < 8 ? "" : strippedData.substring(0, 8);
        return sendTransaction(
                to,
                data,
                value,
                getGasProvider().getGasPrice(methodId),
                getGasProvider().getGasLimit(methodId),
                client.getBlockLimit(),
                abi,
                constructor);
    }

    /**
     * Send tx with gasPrice and gasLimit fields
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param gasPrice price of gas
     * @param gasLimit use limit of gas
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @return receipt
     */
    @Override
    protected TransactionReceipt sendTransaction(
            String to,
            String data,
            BigInteger value,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String abi,
            boolean constructor)
            throws JniException {
        return sendTransaction(
                to, data, value, gasPrice, gasLimit, client.getBlockLimit(), abi, constructor);
    }

    /**
     * Send tx with gasPrice and gasLimit fields
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param gasPrice price of gas
     * @param gasLimit use limit of gas
     * @param blockLimit block limit
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @return receipt
     */
    @Override
    protected TransactionReceipt sendTransaction(
            String to,
            String data,
            BigInteger value,
            BigInteger gasPrice,
            BigInteger gasLimit,
            BigInteger blockLimit,
            String abi,
            boolean constructor)
            throws JniException {
        long transactionData =
                TransactionBuilderV2JniObj.createTransactionData(
                        client.getGroup(),
                        client.getChainId(),
                        to,
                        data,
                        abi,
                        blockLimit.longValue(),
                        Numeric.toHexString(value),
                        Numeric.toHexString(gasPrice),
                        gasLimit.longValue());
        String dataHash =
                TransactionBuilderV2JniObj.calcTransactionDataHash(cryptoType, transactionData);
        CompletableFuture<TransactionReceipt> future = new CompletableFuture<>();
        asyncTxSigner.signAsync(
                Hex.decode(dataHash),
                signature -> {
                    try {
                        int transactionAttribute;
                        if (client.isWASM()) {
                            transactionAttribute = TransactionAttribute.LIQUID_SCALE_CODEC;
                            if (constructor) {
                                transactionAttribute |= TransactionAttribute.LIQUID_CREATE;
                            }
                        } else {
                            transactionAttribute = TransactionAttribute.EVM_ABI_CODEC;
                        }
                        String signedTransaction =
                                TransactionBuilderV2JniObj.createSignedTransaction(
                                        transactionData,
                                        Hex.toHexString(signature.encode()),
                                        dataHash,
                                        transactionAttribute,
                                        client.getExtraData());
                        client.sendTransactionAsync(
                                signedTransaction,
                                false,
                                new TransactionCallback() {
                                    @Override
                                    public void onResponse(TransactionReceipt receipt) {
                                        future.complete(receipt);
                                    }
                                });
                        return 0;
                    } catch (JniException e) {
                        logger.error(
                                "Create sign transaction failed, error message: {}",
                                e.getMessage(),
                                e);
                    }
                    return -1;
                });
        TransactionReceipt transactionReceipt = null;
        try {
            transactionReceipt = future.get();
        } catch (Exception e) {
            logger.error("Get transaction receipt failed, error message: {}", e.getMessage(), e);
        }
        return transactionReceipt;
    }

    /**
     * Send tx with abi field asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param callback callback function
     * @return receipt
     */
    @Override
    protected String asyncSendTransaction(
            String to, String data, BigInteger value, TransactionCallback callback)
            throws JniException {
        String strippedData = Hex.trimPrefix(data);
        String methodId = strippedData.length() < 8 ? "" : strippedData.substring(0, 8);
        return asyncSendTransaction(
                to,
                data,
                value,
                getGasProvider().getGasPrice(methodId),
                getGasProvider().getGasLimit(methodId),
                client.getBlockLimit(),
                null,
                false,
                callback);
    }

    /**
     * Send tx with abi field asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @param callback
     * @return receipt
     */
    @Override
    protected String asyncSendTransaction(
            String to,
            String data,
            BigInteger value,
            String abi,
            boolean constructor,
            TransactionCallback callback)
            throws JniException {
        String strippedData = Hex.trimPrefix(data);
        String methodId = strippedData.length() < 8 ? "" : strippedData.substring(0, 8);
        return asyncSendTransaction(
                to,
                data,
                value,
                getGasProvider().getGasPrice(methodId),
                getGasProvider().getGasLimit(methodId),
                client.getBlockLimit(),
                abi,
                constructor,
                callback);
    }

    /**
     * Send tx with gasPrice and gasLimit fields asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param gasPrice price of gas
     * @param gasLimit use limit of gas
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @param callback callback function
     * @return receipt
     */
    @Override
    protected String asyncSendTransaction(
            String to,
            String data,
            BigInteger value,
            BigInteger gasPrice,
            BigInteger gasLimit,
            String abi,
            boolean constructor,
            TransactionCallback callback)
            throws JniException {
        return asyncSendTransaction(
                to,
                data,
                value,
                gasPrice,
                gasLimit,
                client.getBlockLimit(),
                abi,
                constructor,
                callback);
    }

    /**
     * Send tx with gasPrice and gasLimit fields asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param gasPrice price of gas
     * @param gasLimit use limit of gas
     * @param blockLimit block limit
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @param callback callback function
     * @return receipt
     */
    @Override
    protected String asyncSendTransaction(
            String to,
            String data,
            BigInteger value,
            BigInteger gasPrice,
            BigInteger gasLimit,
            BigInteger blockLimit,
            String abi,
            boolean constructor,
            TransactionCallback callback)
            throws JniException {
        long transactionData =
                TransactionBuilderV2JniObj.createTransactionData(
                        client.getGroup(),
                        client.getChainId(),
                        to,
                        data,
                        abi,
                        blockLimit.longValue(),
                        Numeric.toHexString(value),
                        Numeric.toHexString(gasPrice),
                        gasLimit.longValue());
        String dataHash =
                TransactionBuilderV2JniObj.calcTransactionDataHash(cryptoType, transactionData);

        asyncTxSigner.signAsync(
                Hex.decode(dataHash),
                signature -> {
                    try {
                        int transactionAttribute;
                        if (client.isWASM()) {
                            transactionAttribute = TransactionAttribute.LIQUID_SCALE_CODEC;
                            if (constructor) {
                                transactionAttribute |= TransactionAttribute.LIQUID_CREATE;
                            }
                        } else {
                            transactionAttribute = TransactionAttribute.EVM_ABI_CODEC;
                        }
                        String signedTransaction =
                                TransactionBuilderV2JniObj.createSignedTransaction(
                                        transactionData,
                                        Hex.toHexString(signature.encode()),
                                        dataHash,
                                        transactionAttribute,
                                        client.getExtraData());
                        client.sendTransactionAsync(
                                signedTransaction,
                                false,
                                new TransactionCallback() {
                                    @Override
                                    public void onResponse(TransactionReceipt receipt) {
                                        callback.onResponse(receipt);
                                    }
                                });
                        return 0;
                    } catch (JniException e) {
                        logger.error(
                                "Create sign transaction failed, error message: {}",
                                e.getMessage(),
                                e);
                        callback.onError(
                                -1,
                                "Create sign transaction failed, error message:" + e.getMessage());
                    } catch (Exception e) {
                        logger.error(
                                "Send transaction failed, error message: {}", e.getMessage(), e);
                        callback.onError(
                                -1, "Send transaction failed, error message:" + e.getMessage());
                    }
                    return -1;
                });
        return dataHash;
    }

    /**
     * Send tx with EIP1559
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param eip1559Struct EIP1559 transaction payload
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @return receipt
     */
    @Override
    protected TransactionReceipt sendTransactionEIP1559(
            String to,
            String data,
            BigInteger value,
            EIP1559Struct eip1559Struct,
            String abi,
            boolean constructor)
            throws JniException {
        return sendTransactionEIP1559(
                to, data, value, eip1559Struct, client.getBlockLimit(), abi, constructor);
    }

    /**
     * Send tx with EIP1559
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param eip1559Struct EIP1559 transaction payload
     * @param blockLimit block limit
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     * @param constructor if you deploy contract, should set to be true
     * @return receipt
     */
    @Override
    protected TransactionReceipt sendTransactionEIP1559(
            String to,
            String data,
            BigInteger value,
            EIP1559Struct eip1559Struct,
            BigInteger blockLimit,
            String abi,
            boolean constructor)
            throws JniException {

        long transactionData =
                TransactionBuilderV2JniObj.createEIP1559TransactionData(
                        client.getGroup(),
                        client.getChainId(),
                        to,
                        data,
                        abi,
                        blockLimit.longValue(),
                        Numeric.toHexString(value),
                        eip1559Struct.getGasLimit().longValue(),
                        Numeric.toHexString(eip1559Struct.getMaxFeePerGas()),
                        Numeric.toHexString(eip1559Struct.getMaxPriorityFeePerGas()));
        String dataHash =
                TransactionBuilderV2JniObj.calcTransactionDataHash(cryptoType, transactionData);
        CompletableFuture<TransactionReceipt> future = new CompletableFuture<>();
        asyncTxSigner.signAsync(
                Hex.decode(dataHash),
                signature -> {
                    try {
                        int transactionAttribute;
                        if (client.isWASM()) {
                            transactionAttribute = TransactionAttribute.LIQUID_SCALE_CODEC;
                            if (constructor) {
                                transactionAttribute |= TransactionAttribute.LIQUID_CREATE;
                            }
                        } else {
                            transactionAttribute = TransactionAttribute.EVM_ABI_CODEC;
                        }
                        String signedTransaction =
                                TransactionBuilderV2JniObj.createSignedTransaction(
                                        transactionData,
                                        Hex.toHexString(signature.encode()),
                                        dataHash,
                                        transactionAttribute,
                                        client.getExtraData());
                        client.sendTransactionAsync(
                                signedTransaction,
                                false,
                                new TransactionCallback() {
                                    @Override
                                    public void onResponse(TransactionReceipt receipt) {
                                        future.complete(receipt);
                                    }
                                });
                        return 0;
                    } catch (JniException e) {
                        logger.error(
                                "Create sign transaction failed, error message: {}",
                                e.getMessage(),
                                e);
                    }
                    return -1;
                });
        TransactionReceipt transactionReceipt = null;
        try {
            transactionReceipt = future.get();
        } catch (Exception e) {
            logger.error("Get transaction receipt failed, error message: {}", e.getMessage(), e);
        }
        return transactionReceipt;
    }

    /**
     * Send tx with EIP1559 asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param eip1559Struct EIP1559 transaction payload
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     *     contract
     * @param constructor if you deploy contract, should set to be true
     * @param callback callback function
     * @return receipt
     */
    @Override
    protected String asyncSendTransactionEIP1559(
            String to,
            String data,
            BigInteger value,
            EIP1559Struct eip1559Struct,
            String abi,
            boolean constructor,
            TransactionCallback callback)
            throws JniException {
        return asyncSendTransactionEIP1559(
                to, data, value, eip1559Struct, client.getBlockLimit(), abi, constructor, callback);
    }

    /**
     * Send tx with EIP1559 asynchronously
     *
     * @param to to address
     * @param data input data
     * @param value transfer value
     * @param eip1559Struct EIP1559 transaction payload
     * @param blockLimit block limit
     * @param abi ABI JSON string, generated by compile contract, should fill in when you deploy
     * @param constructor if you deploy contract, should set to be true
     * @param callback callback function
     * @return receipt
     */
    @Override
    protected String asyncSendTransactionEIP1559(
            String to,
            String data,
            BigInteger value,
            EIP1559Struct eip1559Struct,
            BigInteger blockLimit,
            String abi,
            boolean constructor,
            TransactionCallback callback)
            throws JniException {
        long transactionData =
                TransactionBuilderV2JniObj.createEIP1559TransactionData(
                        client.getGroup(),
                        client.getChainId(),
                        to,
                        data,
                        abi,
                        blockLimit.longValue(),
                        Numeric.toHexString(value),
                        eip1559Struct.getGasLimit().longValue(),
                        Numeric.toHexString(eip1559Struct.getMaxFeePerGas()),
                        Numeric.toHexString(eip1559Struct.getMaxPriorityFeePerGas()));
        String dataHash =
                TransactionBuilderV2JniObj.calcTransactionDataHash(cryptoType, transactionData);
        asyncTxSigner.signAsync(
                Hex.decode(dataHash),
                signature -> {
                    try {
                        int transactionAttribute;
                        if (client.isWASM()) {
                            transactionAttribute = TransactionAttribute.LIQUID_SCALE_CODEC;
                            if (constructor) {
                                transactionAttribute |= TransactionAttribute.LIQUID_CREATE;
                            }
                        } else {
                            transactionAttribute = TransactionAttribute.EVM_ABI_CODEC;
                        }
                        String signedTransaction =
                                TransactionBuilderV2JniObj.createSignedTransaction(
                                        transactionData,
                                        Hex.toHexString(signature.encode()),
                                        dataHash,
                                        transactionAttribute,
                                        client.getExtraData());
                        client.sendTransactionAsync(
                                signedTransaction,
                                false,
                                new TransactionCallback() {
                                    @Override
                                    public void onResponse(TransactionReceipt receipt) {
                                        callback.onResponse(receipt);
                                    }
                                });
                        return 0;
                    } catch (JniException e) {
                        logger.error(
                                "Create sign transaction failed, error message: {}",
                                e.getMessage(),
                                e);
                        callback.onError(
                                -1,
                                "Create sign transaction failed, error message:" + e.getMessage());
                    } catch (Exception e) {
                        logger.error(
                                "Send transaction failed, error message: {}", e.getMessage(), e);
                        callback.onError(
                                -1, "Send transaction failed, error message:" + e.getMessage());
                    }
                    return -1;
                });
        return dataHash;
    }

    /**
     * Send call
     *
     * @param to to address
     * @param data input data
     * @return call result
     */
    @Override
    protected Call sendCall(String to, String data) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            outputStream.write(Hex.trimPrefix(to).getBytes());
            outputStream.write(Hex.decode(data));
            byte[] hash = hashImpl.hash(outputStream.toByteArray());
            CompletableFuture<Call> future = new CompletableFuture<>();
            asyncTxSigner.signAsync(
                    hash,
                    signature -> {
                        Call call =
                                client.call(
                                        new Transaction("", to, Hex.decode(data)),
                                        Hex.toHexString(signature.encode()));
                        future.complete(call);
                        return 0;
                    });
            return future.get();
        } catch (Exception e) {
            logger.error("Send call failed, error message: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Send call with signature of call data
     *
     * @param to to address
     * @param data input data
     * @param signature signature of call data
     */
    @Override
    protected Call sendCall(String to, String data, String signature) {
        return client.call(new Transaction("", to, Hex.decode(data)), signature);
    }

    /**
     * Send call asynchronously
     *
     * @param to to address
     * @param data input data
     * @param callback callback function
     */
    @Override
    protected void asyncSendCall(String to, String data, RespCallback<Call> callback) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            outputStream.write(Hex.trimPrefix(to).getBytes());
            outputStream.write(Hex.decode(data));
            byte[] hash = hashImpl.hash(outputStream.toByteArray());
            asyncTxSigner.signAsync(
                    hash,
                    signature -> {
                        client.callAsync(
                                new Transaction("", to, Hex.decode(data)),
                                Hex.toHexString(signature.encode()),
                                callback);
                        return 0;
                    });
        } catch (Exception e) {
            logger.error("Send call failed, error message: {}", e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Send call asynchronously with signature of call data
     *
     * @param to to address
     * @param data input data
     * @param signature signature of call data
     * @param callback callback function
     */
    @Override
    protected void asyncSendCall(
            String to, String data, String signature, RespCallback<Call> callback) {
        client.callAsync(new Transaction("", to, Hex.decode(data)), signature, callback);
    }
}

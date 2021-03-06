//
//  AlamoClient.swift
//  AGECenter
//
//  Created by CavanSu on 2019/6/23.
//  Copyright © 2019 Agora. All rights reserved.
//

import Foundation
import Alamofire

protocol AlamoClientDelegate: NSObjectProtocol {
    func alamo(_ client: AlamoClient, requestSuccess event: AGERequestEvent, startTime: TimeInterval, url: String)
    func alamo(_ client: AlamoClient, requestFail error: AGEError, event: AGERequestEvent, url: String)
}

class AlamoClient: NSObject, RequestClientProtocol, AGELogBase {
    private lazy var instances = [Int: SessionManager]() // Int: taskId
    private lazy var afterWorkers = [String: AfterWorker]() // String: AGERequestEvent name
    
    private var queue: DispatchQueue {
        return DispatchQueue(label: "")
    }
    
    weak var delegate: AlamoClientDelegate?
    
    var logTube: LogTube
    
    init(logTube: LogTube,
         delegate: AlamoClientDelegate?) {
        self.logTube = logTube
        self.delegate = delegate
    }
}

extension AlamoClient {
    func request(task: AGERequestTaskProtocol, success: AGEResponse? = nil, failRetry: ErrorRetryCompletion = nil) {
        privateRequst(task: task, success: success) { [unowned self] (error) in
            guard let eRetry = failRetry else {
                self.removeWorker(of: task.event)
                return
            }
            
            let option = eRetry(error)
            switch option {
            case .retry(let time, let newTask):
                var reTask: AGERequestTaskProtocol
                
                if let newTask = newTask {
                    reTask = newTask
                } else {
                    reTask = task
                }
                
                let work = self.worker(of: reTask.event)
                work.perform(after: time, on: self.queue, {
                    self.request(task: reTask, success: success, failRetry: failRetry)
                })
            case .resign:
                break
            }
        }
    }
    
    func upload(task: AGEUploadTaskProtocol, success: AGEResponse? = nil, failRetry: ErrorRetryCompletion = nil) {
        privateUpload(task: task, success: success) { [unowned self] (error) in
            guard let eRetry = failRetry else {
                self.removeWorker(of: task.event)
                return
            }
            
            let option = eRetry(error)
            switch option {
            case .retry(let time, let newTask):
                var reTask: AGEUploadTaskProtocol
                
                if let newTask = newTask as? AGEUploadTaskProtocol {
                    reTask = newTask
                } else {
                    reTask = task
                }
                
                let work = self.worker(of: reTask.event)
                work.perform(after: time, on: self.queue, {
                    self.upload(task: reTask, success: success, failRetry: failRetry)
                })
            case .resign:
                break
            }
        }
    }
}

// MARK: Request
typealias AGEHttpMethod = HTTPMethod

extension HTTPMethod {
    fileprivate var encoding: ParameterEncoding {
        switch self {
        case .get:   return URLEncoding.default
        case .post:  return JSONEncoding.default
        default:     return JSONEncoding.default
        }
    }
}

private extension AlamoClient {
    func privateRequst(task: AGERequestTaskProtocol, success: AGEResponse?, requestFail: ErrorCompletion) {
        guard let httpMethod = task.requestType.httpMethod else {
            fatalError("Request Type error")
        }
        
        guard var url = task.requestType.url else {
            fatalError("Request Type error")
        }
        
        let timeout = task.timeout.value
        let taskId = task.id
        let startTime = Date.timeIntervalSinceReferenceDate
        let instance = alamo(timeout, id: taskId)
        
        var dataRequest: DataRequest
        
        if httpMethod == .get {
            if let parameters = task.parameters {
                url = urlAddParameters(url: url, parameters: parameters)
            }
            dataRequest = instance.request(url,
                                           method: httpMethod,
                                           encoding: httpMethod.encoding,
                                           headers: task.header)
        } else {
            dataRequest = instance.request(url,
                                           method: httpMethod,
                                           parameters: task.parameters,
                                           encoding: httpMethod.encoding,
                                           headers: task.header)
        }
        
        log(info: "http request, event: \(task.event.description)",
            extra: "url: \(url), parameter: \(OptionsDescription.any(task.parameters))")
        
        dataRequest.responseData { [unowned self] (dataResponse) in
            self.handle(dataResponse: dataResponse,
                        from: task,
                        url: url,
                        startTime: startTime,
                        success: success,
                        fail: requestFail)
            self.removeInstance(taskId)
            self.removeWorker(of: task.event)
        }
    }
    
    func privateUpload(task: AGEUploadTaskProtocol, success: AGEResponse?, requestFail: ErrorCompletion) {
        guard let _ = task.requestType.httpMethod else {
            fatalError("Request Type error")
        }
        
        guard let url = task.requestType.url else {
            fatalError("Request Type error")
        }
        
        let timeout = task.timeout.value
        let taskId = task.id
        let startTime = Date.timeIntervalSinceReferenceDate
        let instance = alamo(timeout, id: taskId)
        
        log(info: "http upload, event: \(task.event.description)",
            extra: "url: \(url), parameter: \(OptionsDescription.any(task.parameters))")
        
        instance.upload(multipartFormData: { (multiData) in
            multiData.append(task.object.fileData,
                             withName: task.object.fileKeyOnServer,
                             fileName: task.object.fileName,
                             mimeType: task.object.mime.text)
            
            guard let parameters = task.parameters else {
                return
            }
            
            for (key, value) in parameters {
                if let stringValue = value as? String,
                    let part = stringValue.data(using: String.Encoding.utf8) {
                    multiData.append(part, withName: key)
                } else if var intValue = value as? Int {
                    let part = Data(bytes: &intValue, count: MemoryLayout<Int>.size)
                    multiData.append(part, withName: key)
                }
            }
        }, to: url, headers: task.header) { (encodingResult) in
            switch encodingResult {
            case .success(let upload, _, _):
                upload.uploadProgress(queue: DispatchQueue.main, closure: { (progress) in
                })
                
                upload.responseData { [unowned self] (dataResponse) in
                    self.handle(dataResponse: dataResponse,
                                from: task,
                                url: url,
                                startTime: startTime,
                                success: success,
                                fail: requestFail)
                    self.removeInstance(taskId)
                    self.removeWorker(of: task.event)
                }
            case .failure(let error):
                let mError = AGEError.fail(error.localizedDescription)
                self.request(error: mError, of: task.event, with: url)
                if let requestFail = requestFail {
                    requestFail(mError)
                }
                self.removeInstance(taskId)
            }
        }
    }
    
    func handle(dataResponse: DataResponse<Data>, from task: AGERequestTaskProtocol, url: String, startTime: TimeInterval, success: AGEResponse?, fail: ErrorCompletion) {
        let result = self.checkResponseData(dataResponse, event: task.event)
        switch result {
        case .pass(let data):
            self.requestSuccess(of: task.event, startTime: startTime, with: url)
            guard let success = success else {
                self.log(info: "request success", extra: "event: \(task.event)")
                break
            }
            
            do {
                switch success {
                case .blank(let completion):
                    self.log(info: "request success", extra: "event: \(task.event)")
                    guard let completion = completion else {
                        break
                    }
                    completion()
                case .data(let completion):
                    self.log(info: "request success", extra: "event: \(task.event), data.count: \(data.count)")
                    guard let completion = completion else {
                        break
                    }
                    try completion(data)
                case .json(let completion):
                    let json = try data.json()
                    self.log(info: "request success", extra: "event: \(task.event), json: \(json.description)")
                    guard let completion = completion else {
                        break
                    }
                    
                    try completion(json)
                }
            } catch let error as AGEError {
                if let fail = fail {
                    fail(error)
                }
                self.log(error: error, extra: "event: \(task.event)")
            } catch {
                if let fail = fail {
                    fail(AGEError.unknown())
                }
                self.log(error: AGEError.unknown(), extra: "event: \(task.event)")
            }
        case .fail(let error):
            self.request(error: error, of: task.event, with: url)
            self.log(error: error, extra: "event: \(task.event), url: \(url)")
            if let fail = fail {
                fail(error)
            }
        }
    }
}

// MARK: Alamo instance
private extension AlamoClient {
    func alamo(_ timeout: TimeInterval, id: Int) -> SessionManager {
        let configuration = URLSessionConfiguration.default
        configuration.httpAdditionalHeaders = SessionManager.defaultHTTPHeaders
        configuration.timeoutIntervalForRequest = timeout
        
        let alamo = Alamofire.SessionManager(configuration: configuration)
        instances[id] = alamo
        return alamo
    }
    
    func removeInstance(_ id: Int) {
        instances.removeValue(forKey: id)
    }
        
    func urlAddParameters(url: String, parameters: [String: Any]) -> String {
        var fullURL = url
        var index: Int = 0

        for (key, value) in parameters {
            if index == 0 {
                fullURL += "?"
            } else {
                fullURL += "&"
            }
            
            fullURL += "\(key)=\(value)"
            index += 1
        }
        return fullURL
    }
    
    func worker(of event: AGERequestEvent) -> AfterWorker {
        var work: AfterWorker
        if let tWork = self.afterWorkers[event.name] {
            work = tWork
        } else {
            work = AfterWorker()
        }
        return work
    }
    
    func removeWorker(of event: AGERequestEvent) {
        afterWorkers.removeValue(forKey: event.name)
    }
}

// MARK: Check Response
private extension AlamoClient {
    enum ResponseCode {
        init(rawValue: Int) {
            if rawValue == 200 {
                self = .success
            } else {
                self = .error(code: rawValue)
            }
        }
        
        case success, error(code: Int)
    }
    
    enum CheckResult {
        case pass, fail(AGEError)
        
        var rawValue: Int {
            switch self {
            case .pass: return 0
            case .fail: return 1
            }
        }
        
        static func ==(left: CheckResult, right: CheckResult) -> Bool {
            return left.rawValue == right.rawValue
        }
        
        static func !=(left: CheckResult, right: CheckResult) -> Bool {
            return left.rawValue != right.rawValue
        }
    }
    
    enum CheckDataResult {
        case pass(Data), fail(AGEError)
    }
    
    func checkResponseData(_ dataResponse: DataResponse<Data>, event: AGERequestEvent) -> CheckDataResult {
        var dataResult: CheckDataResult = .fail(AGEError.unknown())
        var result: CheckResult = .fail(AGEError.unknown())
        let code = dataResponse.response?.statusCode
        let checkIndexs = 3
        
        for index in 0 ..< checkIndexs {
            switch index {
            case 0:  result = checkResponseCode(code, event: event)
            case 1:  result = checkResponseContent(dataResponse.error, event: event)
            case 2:
                if let data = dataResponse.data {
                    dataResult = .pass(data)
                } else {
                    let error =  AGEError.fail("response data nil",
                                               extra: "return data nil, event: \(event.description)")
                    dataResult = .fail(error)
                }
            default: break
            }
            
            var isBreak = false
            
            switch result {
            case .fail(let error): dataResult = .fail(error); isBreak = true;
            case .pass: break
            }
            
            if isBreak {
                break
            }
        }
        
        return dataResult
    }
    
    func checkResponseCode(_ code: Int?, event: AGERequestEvent) -> CheckResult {
        var result: CheckResult = .pass
        
        if let code = code {
            let mCode = ResponseCode(rawValue: code)
            
            switch mCode {
            case .success:
                result = .pass
            case .error(let code):
                let error = AGEError.fail("response code error",
                                          code: code,
                                          extra: "event: \(event.description)")
                result = .fail(error)
            }
        } else {
            let error = AGEError.fail("connect with server error, response code nil",
                                      extra: "event: \(event.description)")
            result = .fail(error)
        }
        return result
    }
    
    func checkResponseContent(_ error: Error?, event: AGERequestEvent) -> CheckResult {
        var result: CheckResult = .pass
        
        if let error = error as? AFError {
            let mError = AGEError.fail(error.localizedDescription,
                                       code: error.responseCode,
                                       extra: "Alamofire, event: \(event.description)")
            result = .fail(mError)
        } else if let error = error {
            let mError = AGEError.fail(error.localizedDescription,
                                       extra: "Http, event: \(event.description)")
            result = .fail(mError)
        }
        return result
    }
}

// MARK: Callback
private extension AlamoClient {
    func requestSuccess(of event: AGERequestEvent, startTime: TimeInterval, with url: String) {
        self.delegate?.alamo(self, requestSuccess: event, startTime: startTime, url: url)
    }
    
    func request(error: AGEError, of event: AGERequestEvent, with url: String) {
        self.delegate?.alamo(self, requestFail: error, event: event, url: url)
    }
}

// MARK: Log
private extension AlamoClient {
    func log(info: String, extra: String? = nil, funcName: String = #function) {
        let className = AlamoClient.self
        logOutputInfo(info, extra: extra, className: className, funcName: funcName)
    }
    
    func log(warning: String, extra: String? = nil, funcName: String = #function) {
        let className = AlamoClient.self
        logOutputWarning(warning, extra: extra, className: className, funcName: funcName)
    }
    
    func log(error: Error, extra: String? = nil, funcName: String = #function) {
        let className = AlamoClient.self
        logOutputError(error, extra: extra, className: className, funcName: funcName)
    }
}
